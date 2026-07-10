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
 *     and joins the call using the `wsToken` carried in the payload.
 *   - The webview calls `endCallkitCall` when a call ends (hangup, answered
 *     in-app, server cancel) so the green system call UI goes away.
 *
 * Everything CallKit-related runs on the main queue (PushKit is created with
 * `.main`, the CXProvider delegate queue is nil = main), so the call-state
 * dictionaries need no locking.
 */
/// One Accept / Decline / End tap on the native call screen, queued until
/// the webview drains it. Field names are what the webview consumes
/// (camelCase over the Tauri channel).
struct PendingCallAction: Encodable {
  /// "answer" | "decline" (un-answered End tap) | "end" (answered End tap)
  var kind: String
  var callId: String
  /// Signed token the app uses to join the call — present on "answer".
  var wsToken: String
  var from: String
  var personName: String
  var flowName: String
}

struct EndCallkitCallArgs: Decodable {
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

  /// Give APNs ample time on slow networks before failing the invoke.
  private static let tokenTimeoutSeconds: TimeInterval = 30

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
  /// Accept/Decline/End taps not yet drained by the webview. A cold-started
  /// webview asks for these after it boots; a live webview drains on the
  /// `call_action` event.
  fileprivate var pendingCallActions: [PendingCallAction] = []

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
        DispatchQueue.main.asyncAfter(deadline: .now() + VoipPushPlugin.tokenTimeoutSeconds) {
          self.rejectPending("timed out waiting for APNs device token")
        }
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
    for invoke in drainPending() {
      invoke.reject(message)
    }
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
      let actions = self.pendingCallActions
      self.pendingCallActions = []
      invoke.resolve(DrainCallActionsResponse(actions: actions))
    }
  }

  /// The webview reports the call is over (hangup, answered in-app, server
  /// cancel) — dismiss the system call UI if it's still up.
  @objc func endCallkitCall(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(EndCallkitCallArgs.self)
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
  /// `ws_token`, `from`, `to`, `person_name`, `flow_name`, `flow_id`,
  /// `reason`, `expires_at` (ms epoch).
  private func handleVoipPush(_ dict: [AnyHashable: Any]) {
    let action = dict["action"] as? String ?? ""
    let callId = dict["call_id"] as? String ?? ""

    if action == "cancel" {
      // Answered elsewhere / caller hung up / ring timed out. iOS demands
      // that EVERY VoIP push reports a call, so if we never rang for this
      // callId (push arrived out of order) flash-report one and end it.
      let reason = dict["reason"] as? String ?? ""
      let cxReason: CXCallEndedReason = reason.contains("answer") ? .answeredElsewhere : .remoteEnded
      if !callId.isEmpty, uuidByCallId[callId] != nil {
        endSystemCall(callId: callId, reason: cxReason)
      } else {
        reportAndImmediatelyEnd(reason: cxReason)
      }
      return
    }

    guard action == "ring", !callId.isEmpty, let wsToken = dict["ws_token"] as? String,
      !wsToken.isEmpty
    else {
      NSLog("[voip-push] malformed VoIP push (action=%@) — reporting throwaway call", action)
      reportAndImmediatelyEnd(reason: .failed)
      return
    }

    let from = dict["from"] as? String ?? ""
    let personName = dict["person_name"] as? String ?? ""
    let flowName = dict["flow_name"] as? String ?? ""
    let expiresAt = (dict["expires_at"] as? NSNumber)?.doubleValue ?? 0
    let nowMs = Date().timeIntervalSince1970 * 1000

    let uuid = UUID()
    uuidByCallId[callId] = uuid
    callIdByUuid[uuid] = callId
    ringInfoByCallId[callId] = PendingCallAction(
      kind: "answer", callId: callId, wsToken: wsToken, from: from,
      personName: personName, flowName: flowName)

    let update = CXCallUpdate()
    update.hasVideo = false
    if !from.isEmpty {
      update.remoteHandle = CXHandle(type: .phoneNumber, value: from)
    }
    // The caller's name when known, else "Line · number".
    if !personName.isEmpty {
      update.localizedCallerName = personName
    } else if !flowName.isEmpty {
      update.localizedCallerName = from.isEmpty ? flowName : "\(flowName) · \(from)"
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

  private func cleanUpCall(_ callId: String) {
    if let uuid = uuidByCallId.removeValue(forKey: callId) {
      callIdByUuid.removeValue(forKey: uuid)
    }
    ringInfoByCallId.removeValue(forKey: callId)
    answeredCallIds.remove(callId)
  }

  /// Queue an action for the webview and announce it. A live webview drains
  /// on the event; a cold-started one drains after it boots.
  private func enqueueCallAction(_ action: PendingCallAction) {
    pendingCallActions.append(action)
    do {
      try trigger("call_action", data: action)
    } catch {
      NSLog("[voip-push] call_action trigger failed: %@", error.localizedDescription)
    }
  }

  // MARK: CXProviderDelegate

  public func providerDidReset(_ provider: CXProvider) {
    uuidByCallId.removeAll()
    callIdByUuid.removeAll()
    ringInfoByCallId.removeAll()
    answeredCallIds.removeAll()
  }

  public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
    guard let callId = callIdByUuid[action.callUUID], let ring = ringInfoByCallId[callId] else {
      action.fail()
      return
    }
    answeredCallIds.insert(callId)
    enqueueCallAction(ring)  // kind == "answer", carries wsToken/from/names
    // Note: iOS does NOT foreground the app on a lock-screen answer; the
    // webview joins the audio leg when the user opens the app (CallKit
    // shows our icon on the in-call screen). Foreground answers join
    // immediately via the call_action event.
    action.fulfill()
  }

  public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
    guard let callId = callIdByUuid[action.callUUID] else {
      // Nothing we know about (throwaway report) — just satisfy CallKit.
      action.fulfill()
      return
    }
    let kind = answeredCallIds.contains(callId) ? "end" : "decline"
    enqueueCallAction(
      PendingCallAction(
        kind: kind, callId: callId, wsToken: "", from: "", personName: "", flowName: ""))
    cleanUpCall(callId)
    action.fulfill()
  }
}
