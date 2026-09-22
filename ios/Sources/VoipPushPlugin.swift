import AVFoundation
import CallKit
import ObjectiveC
import PushKit
import Tauri
import UIKit
import UserNotifications
import WebKit

/**
 * APNs push-token acquisition.
 *
 * The webview invokes `plugin:voip-push|register_for_push`, which lands here
 * as `registerForPush`. Flow:
 *
 *   1. Request notification permission (`UNUserNotificationCenter`).
 *   2. Inject `application:didRegisterForRemoteNotificationsWithDeviceToken:`
 *      into Tauri's app delegate class at runtime — Tauri owns the
 *      `UIApplicationDelegate` and (as of Tauri 2) does not implement or
 *      forward that callback, so `class_addMethod` is the supported-adjacent
 *      escape hatch (the same approach every Tauri push plugin uses). If a
 *      future Tauri version implements it, we chain in front of the original.
 *   3. `registerForRemoteNotifications`, then resolve the pending invoke with
 *      the hex token + `identifierForVendor` + app version.
 *
 * VoIP + CallKit ringing:
 *
 *   - `init` registers a `PKPushRegistry` for `.voIP`, so the VoIP token is
 *     acquired on every launch (including a background launch triggered by a
 *     VoIP push). The token reaches the webview via the `registerForPush`
 *     response (`voipToken`) and, on rotation, the `voip_token` plugin event.
 *   - An incoming VoIP push (see README for the expected payload shape) is
 *     reported to CallKit **synchronously** — Apple terminates (and
 *     eventually blocklists) apps that receive a VoIP push without reporting
 *     a call, so even malformed/expired pushes report a call and immediately
 *     end it.
 *   - Accept / Decline taps on the native call screen are queued as
 *     `PendingCallAction`s and announced with a `call_action` plugin event.
 *     The webview drains the queue (`drainPendingCallActions`) — on the live
 *     event when it's running, or after its own startup on a cold start —
 *     and joins the call using the `joinToken` carried in the payload.
 *   - The webview calls `endCall` when a call ends (hangup, answered
 *     in-app, server cancel) so the green system call UI goes away.
 *   - Audio: the shared `AVAudioSession` is configured for a voice call
 *     (`.playAndRecord` / `.voiceChat`) before every `reportNewIncomingCall`
 *     and again on Answer, as Apple requires — CallKit then activates it and
 *     calls `provider(_:didActivate:)`, which is forwarded to the webview as
 *     the `audio_session` event so it can (re)start its WebRTC audio graph
 *     the moment the session is actually live.
 *
 * Everything CallKit-related runs on the main queue (PushKit is created with
 * `.main`, the CXProvider delegate queue is nil = main), so the call-state
 * dictionaries need no locking.
 */
/// One Accept / Decline / End tap on the native call screen, queued until
/// the webview drains it. Field names are what the webview consumes
/// (camelCase over the Tauri channel). `joinToken`/`from`/`personName`/
/// `lineName` are only present on "answer" actions.
struct PendingCallAction: Encodable {
  static let kindAnswer = "answer"
  static let kindDecline = "decline"
  static let kindEnd = "end"

  /// `kindAnswer` | `kindDecline` (un-answered End tap) | `kindEnd` (answered End tap)
  var kind: String
  var callId: String
  /// Signed token the app uses to join the call — present on "answer".
  var joinToken: String?
  var from: String?
  var personName: String?
  var lineName: String?
}

/// A queued action plus when it was queued, so stale entries (and the
/// tokens they carry) can be dropped instead of lingering forever.
private struct QueuedCallAction {
  let action: PendingCallAction
  let enqueuedAt: Date
}

struct EndCallArgs: Decodable {
  let callId: String
}

struct DrainCallActionsResponse: Encodable {
  let actions: [PendingCallAction]
}

class VoipPushPlugin: Plugin {
  /// The live plugin instance, for the injected delegate blocks. Set on load.
  private static var instance: VoipPushPlugin?

  /// Invokes waiting for the APNs token. Guarded by `pendingLock`.
  private var pendingInvokes: [Invoke] = []
  private let pendingLock = NSLock()
  private var delegateHooksInstalled = false

  /// APNs token-acquisition timeout. Generous enough for slow cellular
  /// networks; cancelled the moment the token (or a failure) arrives so a
  /// stale timer can never reject a later registration. Main-queue only.
  private static let tokenTimeoutSeconds: TimeInterval = 30
  private var tokenTimeoutWork: DispatchWorkItem?

  /// Un-drained call actions are dropped past this cap / age — a stuck
  /// webview must not let taps (and their join tokens) pile up forever.
  private static let maxPendingCallActions = 50
  private static let maxPendingActionAge: TimeInterval = 24 * 60 * 60

  /// How long a cancel that beat its ring push suppresses that ring.
  private static let cancelTombstoneTTL: TimeInterval = 60
  private static let answerCallbackTimeoutSeconds: TimeInterval = 10

  /// The host app's user-visible name, for CallKit surfaces.
  fileprivate static var appDisplayName: String {
    (Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
      ?? (Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String)
      ?? "App"
  }

  // MARK: - VoIP + CallKit state (main-queue only)

  /// Hex PushKit VoIP token, once Apple delivers it. Sent to the webview in
  /// the `registerForPush` response and via the `voip_token` event on
  /// rotation.
  fileprivate var voipToken: String?
  private var pushRegistry: PKPushRegistry?
  fileprivate var callProvider: CXProvider?
  /// CallKit wants UUIDs; the payload keys everything by `callId` strings.
  fileprivate var uuidByCallId: [String: UUID] = [:]
  fileprivate var callIdByUuid: [UUID: String] = [:]
  /// The ring-push payload fields the webview needs to join the call, kept
  /// until the user answers or the ring dies.
  fileprivate var ringInfoByCallId: [String: PendingCallAction] = [:]
  /// Calls the user answered — an End tap on these is a hangup request,
  /// not a decline.
  fileprivate var answeredCallIds: Set<String> = []
  /// Optional `answer_url` from the ring push, POSTed on a native Answer.
  /// Kept out of `PendingCallAction` so the JS contract is unchanged.
  fileprivate var answerUrlByCallId: [String: URL] = [:]
  /// Cancels that arrived before their ring (APNs doesn't guarantee
  /// ordering), so the late ring doesn't ring for its full lifetime.
  private var cancelledBeforeRing: [String: Date] = [:]
  /// Accept/Decline/End taps not yet drained by the webview. A cold-started
  /// webview asks for these after it boots; a live webview drains on the
  /// `call_action` event. Deduped, capped, and age-expired — see
  /// `enqueueCallAction` / `prunePendingCallActions`.
  private var pendingCallActions: [QueuedCallAction] = []

  override init() {
    super.init()
    VoipPushPlugin.instance = self
    DispatchQueue.main.async {
      self.setUpCallKit()
      self.setUpPushKit()
    }
  }

  override public func load(webview: WKWebView) {
    VoipPushPlugin.instance = self
  }

  @objc public func registerForPush(_ invoke: Invoke) {
    let center = UNUserNotificationCenter.current()
    let options: UNAuthorizationOptions = [.alert, .badge, .sound]
    center.requestAuthorization(options: options) { granted, error in
      if let error = error {
        invoke.reject("notification permission error: \(error.localizedDescription)")
        return
      }
      guard granted else {
        invoke.reject("notification permission denied")
        return
      }
      DispatchQueue.main.async {
        self.installDelegateHooks()
        self.enqueue(invoke)
        UIApplication.shared.registerForRemoteNotifications()
        // (Re)arm the timeout as a cancellable work item: success or failure
        // cancels it, so it can never fire into a later registration.
        self.tokenTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
          self?.tokenTimeoutWork = nil
          self?.rejectPending("timed out waiting for APNs device token")
        }
        self.tokenTimeoutWork = work
        DispatchQueue.main.asyncAfter(
          deadline: .now() + VoipPushPlugin.tokenTimeoutSeconds, execute: work)
      }
    }
  }

  // MARK: - Pending invoke bookkeeping

  private func enqueue(_ invoke: Invoke) {
    pendingLock.lock()
    pendingInvokes.append(invoke)
    pendingLock.unlock()
  }

  private func drainPending() -> [Invoke] {
    pendingLock.lock()
    let invokes = pendingInvokes
    pendingInvokes = []
    pendingLock.unlock()
    return invokes
  }

  fileprivate func resolvePending(token: String) {
    cancelTokenTimeout()
    let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
    let appVersion =
      Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    let voip = voipToken ?? ""
    for invoke in drainPending() {
      invoke.resolve([
        "token": token,
        "voipToken": voip,
        "deviceId": deviceId,
        "appVersion": appVersion,
        "platform": "ios",
      ])
    }
  }

  fileprivate func rejectPending(_ message: String) {
    cancelTokenTimeout()
    for invoke in drainPending() {
      invoke.reject(message)
    }
  }

  /// Main-queue only (APNs delegate callbacks and the timeout both land
  /// on the main queue).
  private func cancelTokenTimeout() {
    tokenTimeoutWork?.cancel()
    tokenTimeoutWork = nil
  }

  // MARK: - App-delegate hook injection

  /// Add the remote-notification callbacks to Tauri's app delegate class.
  /// Must run on the main thread, after launch (the delegate exists by the
  /// time the webview — and therefore any invoke — is alive).
  private func installDelegateHooks() {
    guard !delegateHooksInstalled else { return }
    guard let delegate = UIApplication.shared.delegate else {
      NSLog("[voip-push] no app delegate; cannot install APNs callbacks")
      return
    }
    delegateHooksInstalled = true
    let cls: AnyClass = object_getClass(delegate)!

    // application:didRegisterForRemoteNotificationsWithDeviceToken:
    let okSel = sel_registerName("application:didRegisterForRemoteNotificationsWithDeviceToken:")
    let okBlock: @convention(block) (AnyObject, UIApplication, NSData) -> Void = { _, _, data in
      let token = (data as Data).map { String(format: "%02x", $0) }.joined()
      NSLog("[voip-push] APNs token acquired (%lu bytes)", UInt(data.length))
      VoipPushPlugin.instance?.resolvePending(token: token)
    }
    let okImp = imp_implementationWithBlock(okBlock as Any)
    if !class_addMethod(cls, okSel, okImp, "v@:@@") {
      // Delegate already implements it (e.g. a future Tauri version) — chain
      // in front of the original implementation instead of replacing it.
      let original = class_getInstanceMethod(cls, okSel)!
      let originalImp = method_getImplementation(original)
      typealias TokenFn = @convention(c) (AnyObject, Selector, UIApplication, NSData) -> Void
      let originalFn = unsafeBitCast(originalImp, to: TokenFn.self)
      let chained: @convention(block) (AnyObject, UIApplication, NSData) -> Void = {
        target, app, data in
        let token = (data as Data).map { String(format: "%02x", $0) }.joined()
        VoipPushPlugin.instance?.resolvePending(token: token)
        originalFn(target, okSel, app, data)
      }
      method_setImplementation(original, imp_implementationWithBlock(chained as Any))
    }

    // application:didFailToRegisterForRemoteNotificationsWithError:
    let failSel = sel_registerName("application:didFailToRegisterForRemoteNotificationsWithError:")
    let failBlock: @convention(block) (AnyObject, UIApplication, NSError) -> Void = { _, _, error in
      NSLog("[voip-push] APNs registration failed: %@", error.localizedDescription)
      VoipPushPlugin.instance?.rejectPending(
        "APNs registration failed: \(error.localizedDescription)")
    }
    class_addMethod(cls, failSel, imp_implementationWithBlock(failBlock as Any), "v@:@@")
  }
}

@_cdecl("init_plugin_voip_push")
func initPlugin() -> Plugin {
  return VoipPushPlugin()
}

// MARK: - VoIP push (PushKit) + native call UI (CallKit)

extension VoipPushPlugin: PKPushRegistryDelegate, CXProviderDelegate {

  fileprivate func setUpPushKit() {
    let registry = PKPushRegistry(queue: .main)
    registry.delegate = self
    registry.desiredPushTypes = [.voIP]
    pushRegistry = registry
  }

  fileprivate func setUpCallKit() {
    let config: CXProviderConfiguration
    if #available(iOS 14.0, *) {
      config = CXProviderConfiguration()
    } else {
      config = CXProviderConfiguration(localizedName: VoipPushPlugin.appDisplayName)
    }
    config.supportsVideo = false
    config.maximumCallGroups = 1
    config.maximumCallsPerCallGroup = 1
    config.supportedHandleTypes = [.phoneNumber, .generic]
    let provider = CXProvider(configuration: config)
    provider.setDelegate(self, queue: nil)  // nil = main queue
    callProvider = provider
  }

  // MARK: JS-facing commands

  /// Return (and clear) the queued call actions. The webview calls this
  /// after it boots on a cold start, and again whenever a `call_action`
  /// event fires while it's alive — draining is what dedupes the two paths.
  @objc func drainPendingCallActions(_ invoke: Invoke) {
    DispatchQueue.main.async {
      self.prunePendingCallActions()
      let actions = self.pendingCallActions.map { $0.action }
      self.pendingCallActions = []
      invoke.resolve(DrainCallActionsResponse(actions: actions))
    }
  }

  /// The webview reports the call is over (hangup, answered in-app, server
  /// cancel) — dismiss the system call UI if it's still up.
  @objc func endCall(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(EndCallArgs.self)
    DispatchQueue.main.async {
      self.endSystemCall(callId: args.callId, reason: .remoteEnded)
      invoke.resolve()
    }
  }

  // MARK: PKPushRegistryDelegate

  public func pushRegistry(
    _ registry: PKPushRegistry, didUpdate credentials: PKPushCredentials, for type: PKPushType
  ) {
    guard type == .voIP else { return }
    let token = credentials.token.map { String(format: "%02x", $0) }.joined()
    NSLog("[voip-push] VoIP token acquired (%lu bytes)", UInt(credentials.token.count))
    voipToken = token
    // Rotation while the app runs: tell the webview so it re-registers. On
    // a fresh launch the token is usually here before `registerForPush`
    // resolves and rides along in that response instead.
    trigger("voip_token", data: ["token": token])
  }

  public func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
    guard type == .voIP else { return }
    NSLog("[voip-push] VoIP token invalidated")
    voipToken = nil
  }

  public func pushRegistry(
    _ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
    for type: PKPushType, completion: @escaping () -> Void
  ) {
    guard type == .voIP else {
      completion()
      return
    }
    handleVoipPush(payload.dictionaryPayload)
    completion()
  }

  /// Expected payload shape (see README): flat keys `action`, `call_id`,
  /// `join_token`, `from`, `person_name`, `line_name`, `reason`,
  /// `expires_at` (ms epoch), `answer_url` (optional).
  private func handleVoipPush(_ dict: [AnyHashable: Any]) {
    let action = dict["action"] as? String ?? ""
    let callId = dict["call_id"] as? String ?? ""
    pruneCancelTombstones()

    if action == "cancel" {
      // Answered elsewhere / caller hung up / ring timed out. iOS demands
      // that EVERY VoIP push reports a call, so if we never rang for this
      // callId (push arrived out of order) flash-report one and end it.
      let reason = dict["reason"] as? String ?? ""
      let isAnsweredReason = reason.contains("answer")
      let cxReason: CXCallEndedReason = isAnsweredReason ? .answeredElsewhere : .remoteEnded
      if !callId.isEmpty, answeredCallIds.contains(callId) {
        if isAnsweredReason {
          // Answered on THIS device — the server's answered-elsewhere fan-out
          // is for the user's other surfaces; ending here would kill the live
          // system call (and its audio session) mid-conversation. The webview
          // ends it via `endCall` when the call is actually over.
          if let uuid = uuidByCallId[callId] {
            callProvider?.reportCall(with: uuid, updated: CXCallUpdate())
          }
          return
        }
        // Caller hung up / ring timed out before the webview joined.
        enqueueCallAction(PendingCallAction(kind: PendingCallAction.kindEnd, callId: callId))
        endSystemCall(callId: callId, reason: .remoteEnded)
        return
      }
      if !callId.isEmpty, uuidByCallId[callId] != nil {
        endSystemCall(callId: callId, reason: cxReason)
      } else {
        if !callId.isEmpty {
          cancelledBeforeRing[callId] = Date()
        }
        reportAndImmediatelyEnd(reason: cxReason)
      }
      return
    }

    guard action == "ring", !callId.isEmpty, let joinToken = dict["join_token"] as? String,
      !joinToken.isEmpty
    else {
      NSLog("[voip-push] malformed VoIP push (action=%@) — reporting throwaway call", action)
      reportAndImmediatelyEnd(reason: .failed)
      return
    }

    if cancelledBeforeRing[callId] != nil {
      NSLog("[voip-push] ring for %@ was already cancelled — not ringing", callId)
      reportAndImmediatelyEnd(reason: .answeredElsewhere)
      return
    }

    let from = dict["from"] as? String ?? ""
    let personName = dict["person_name"] as? String ?? ""
    let lineName = dict["line_name"] as? String ?? ""
    let expiresAt = (dict["expires_at"] as? NSNumber)?.doubleValue ?? 0
    let nowMs = Date().timeIntervalSince1970 * 1000

    let uuid = UUID()
    uuidByCallId[callId] = uuid
    callIdByUuid[uuid] = callId
    ringInfoByCallId[callId] = PendingCallAction(
      kind: PendingCallAction.kindAnswer, callId: callId, joinToken: joinToken,
      from: from.isEmpty ? nil : from,
      personName: personName.isEmpty ? nil : personName,
      lineName: lineName.isEmpty ? nil : lineName)
    if let answerUrlString = dict["answer_url"] as? String,
      let answerUrl = URL(string: answerUrlString),
      let scheme = answerUrl.scheme?.lowercased(), scheme == "https" || scheme == "http"
    {
      answerUrlByCallId[callId] = answerUrl
    }

    configureCallAudioSession()

    let update = CXCallUpdate()
    update.hasVideo = false
    if !from.isEmpty {
      update.remoteHandle = CXHandle(type: .phoneNumber, value: from)
    }
    // The caller's name when known, else "Line · number".
    if !personName.isEmpty {
      update.localizedCallerName = personName
    } else if !lineName.isEmpty {
      update.localizedCallerName = from.isEmpty ? lineName : "\(lineName) · \(from)"
    }

    callProvider?.reportNewIncomingCall(with: uuid, update: update) { error in
      if let error = error {
        // e.g. Do Not Disturb with no exception — clean up so a stale ring
        // can't be answered later.
        NSLog("[voip-push] reportNewIncomingCall failed: %@", error.localizedDescription)
        self.cleanUpCall(callId)
        return
      }
      // Already expired (APNs store-and-forward edge) — ring is a dud.
      if expiresAt > 0, expiresAt <= nowMs {
        self.endSystemCall(callId: callId, reason: .unanswered)
        return
      }
      // Belt-and-braces local expiry: the server should also push a cancel
      // when the ring deadline lapses, but if that push is lost the ring
      // must not stay up forever.
      if expiresAt > nowMs {
        let delay = (expiresAt - nowMs) / 1000
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
          if self.uuidByCallId[callId] != nil, !self.answeredCallIds.contains(callId) {
            self.endSystemCall(callId: callId, reason: .unanswered)
          }
        }
      }
    }
  }

  /// Apple's rule: a VoIP push we can't turn into a real ring still must
  /// report a call before the completion handler — report one and kill it.
  private func reportAndImmediatelyEnd(reason: CXCallEndedReason) {
    guard let provider = callProvider else { return }
    let uuid = UUID()
    let update = CXCallUpdate()
    update.localizedCallerName = VoipPushPlugin.appDisplayName
    provider.reportNewIncomingCall(with: uuid, update: update) { _ in
      provider.reportCall(with: uuid, endedAt: nil, reason: reason)
    }
  }

  /// Dismiss the system call UI (if up) and forget the call.
  fileprivate func endSystemCall(callId: String, reason: CXCallEndedReason) {
    if let uuid = uuidByCallId[callId] {
      callProvider?.reportCall(with: uuid, endedAt: nil, reason: reason)
    }
    cleanUpCall(callId)
  }

  /// Put the shared audio session in voice-call shape. CallKit owns
  /// activation (never call `setActive` here — it races the system and
  /// breaks the in-call audio route); this only sets the category/mode so
  /// that when CallKit activates it, the webview's WebRTC capture and
  /// playback come up on a call-grade route (receiver/Bluetooth, not the
  /// media speaker) instead of failing silently.
  private func configureCallAudioSession() {
    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(
        .playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
    } catch {
      NSLog("[voip-push] audio session configure failed: %@", error.localizedDescription)
    }
  }

  private func cleanUpCall(_ callId: String) {
    if let uuid = uuidByCallId.removeValue(forKey: callId) {
      callIdByUuid.removeValue(forKey: uuid)
    }
    ringInfoByCallId.removeValue(forKey: callId)
    answerUrlByCallId.removeValue(forKey: callId)
    answeredCallIds.remove(callId)
  }

  /// Main-queue only.
  private func pruneCancelTombstones() {
    let cutoff = Date().addingTimeInterval(-VoipPushPlugin.cancelTombstoneTTL)
    cancelledBeforeRing = cancelledBeforeRing.filter { $0.value >= cutoff }
  }

  /// Tell the server this device answered, without waiting for the webview
  /// (which can't run JS until the user opens the app after a lock-screen
  /// answer). Fire-and-forget; a background task lets it finish while
  /// suspended. Never logs the URL or the join token.
  private func postAnswerCallback(url: URL, callId: String, joinToken: String) {
    let body: [String: String] = [
      "call_id": callId,
      "join_token": joinToken,
      "device_id": UIDevice.current.identifierForVendor?.uuidString ?? "unknown",
    ]
    guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }
    var request = URLRequest(url: url, timeoutInterval: VoipPushPlugin.answerCallbackTimeoutSeconds)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = data

    let app = UIApplication.shared
    // Only touched on the main queue (expiration handler + hop below).
    var backgroundTask = UIBackgroundTaskIdentifier.invalid
    let endBackgroundTask: () -> Void = {
      guard backgroundTask != .invalid else { return }
      app.endBackgroundTask(backgroundTask)
      backgroundTask = .invalid
    }
    backgroundTask = app.beginBackgroundTask(withName: "voip-push.answer-callback") {
      NSLog("[voip-push] answer callback for %@ ran out of background time", callId)
      endBackgroundTask()
    }
    let task = URLSession.shared.dataTask(with: request) { _, response, error in
      if let error = error {
        NSLog(
          "[voip-push] answer callback for %@ failed: error %ld", callId, (error as NSError).code)
      } else {
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        NSLog("[voip-push] answer callback for %@: HTTP %ld", callId, statusCode)
      }
      DispatchQueue.main.async { endBackgroundTask() }
    }
    task.resume()
  }

  /// Queue an action for the webview and announce it. A live webview drains
  /// on the event; a cold-started one drains after it boots. Identical
  /// kind+callId actions are deduped, and the queue is capped so a webview
  /// that never drains cannot accumulate tokens indefinitely.
  private func enqueueCallAction(_ action: PendingCallAction) {
    prunePendingCallActions()
    let isDuplicate = pendingCallActions.contains {
      $0.action.kind == action.kind && $0.action.callId == action.callId
    }
    if !isDuplicate {
      pendingCallActions.append(QueuedCallAction(action: action, enqueuedAt: Date()))
      if pendingCallActions.count > VoipPushPlugin.maxPendingCallActions {
        pendingCallActions.removeFirst(
          pendingCallActions.count - VoipPushPlugin.maxPendingCallActions)
      }
    }
    do {
      try trigger("call_action", data: action)
    } catch {
      NSLog("[voip-push] call_action trigger failed: %@", error.localizedDescription)
    }
  }

  /// Drop un-drained actions past their shelf life — the calls they belong
  /// to are long over. Main-queue only.
  private func prunePendingCallActions() {
    let cutoff = Date().addingTimeInterval(-VoipPushPlugin.maxPendingActionAge)
    pendingCallActions.removeAll { $0.enqueuedAt < cutoff }
  }

  // MARK: CXProviderDelegate

  public func providerDidReset(_ provider: CXProvider) {
    uuidByCallId.removeAll()
    callIdByUuid.removeAll()
    ringInfoByCallId.removeAll()
    answerUrlByCallId.removeAll()
    answeredCallIds.removeAll()
  }

  public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
    guard let callId = callIdByUuid[action.callUUID], let ring = ringInfoByCallId[callId] else {
      action.fail()
      return
    }
    answeredCallIds.insert(callId)
    configureCallAudioSession()
    enqueueCallAction(ring)  // kind == kindAnswer, carries joinToken/from/names
    if let answerUrl = answerUrlByCallId.removeValue(forKey: callId),
      let joinToken = ring.joinToken
    {
      postAnswerCallback(url: answerUrl, callId: callId, joinToken: joinToken)
    }
    // Note: iOS does NOT foreground the app on a lock-screen answer; the
    // webview joins the audio leg when the user opens the app (CallKit
    // shows our icon on the in-call screen). Foreground answers join
    // immediately via the call_action event.
    action.fulfill()
  }

  /// CallKit activated our audio session (after Answer, or when the system
  /// hands audio back after an interruption). Tell the webview so it can
  /// resume its audio graph now rather than on a timer.
  public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
    NSLog("[voip-push] audio session activated")
    trigger("audio_session", data: ["active": true])
  }

  public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
    NSLog("[voip-push] audio session deactivated")
    trigger("audio_session", data: ["active": false])
  }

  public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
    guard let callId = callIdByUuid[action.callUUID] else {
      // Nothing we know about (throwaway report) — just satisfy CallKit.
      action.fulfill()
      return
    }
    let kind =
      answeredCallIds.contains(callId)
      ? PendingCallAction.kindEnd : PendingCallAction.kindDecline
    enqueueCallAction(PendingCallAction(kind: kind, callId: callId))
    cleanUpCall(callId)
    action.fulfill()
  }
}
