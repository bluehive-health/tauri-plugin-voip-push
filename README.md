# tauri-plugin-voip-push

VoIP push calling for [Tauri v2](https://v2.tauri.app) mobile apps:

- **Push-token acquisition** — APNs device token (iOS) and FCM registration
  token (Android), plus a PushKit **VoIP token** on iOS, resolved to your
  webview with one command.
- **Native incoming-call UI** — VoIP ring pushes are presented as real system
  calls: **CallKit** on iOS, **Telecom (self-managed ConnectionService) + a
  CallStyle full-screen notification** on Android. Rings work when the app is
  backgrounded or killed, over the lock screen.
- **Action bridge** — Answer / Decline / End taps on the native surfaces are
  queued durably and delivered to your webview, which joins the call however
  your app does calls (WebSocket, WebRTC, …).

On desktop the plugin compiles but `register_for_push` rejects with an
"unsupported" error, so one code path runs everywhere.

## Install

`src-tauri/Cargo.toml` (not yet published to crates.io — use the git URL and
pin a rev/tag; note the crate uses a Cargo `links` key, so only one version
can exist in your dependency tree):

```toml
[dependencies]
tauri-plugin-voip-push = { git = "https://github.com/bluehive-health/tauri-plugin-voip-push" }
```

`src-tauri/src/lib.rs`:

```rust
tauri::Builder::default()
    .plugin(tauri_plugin_voip_push::init())
```

`src-tauri/capabilities/default.json`:

```json
{
  "permissions": ["voip-push:default"]
}
```

> **Loading remote or untrusted content?** Do not grant `voip-push:default`
> to that window — drained call actions carry signed call-join tokens, and
> `end_call` can kill live calls. Grant `voip-push:minimal` instead (token
> registration + rotation events only). See [Security](#security).

### iOS setup

- Entitlements: `aps-environment` (push) and background modes
  `remote-notification` + `voip` in `Info.plist`:

  ```xml
  <key>UIBackgroundModes</key>
  <array>
    <string>remote-notification</string>
    <string>voip</string>
  </array>
  ```

  > Apple's contract: **every** VoIP push must be reported to CallKit. This
  > plugin does that unconditionally (malformed/expired pushes report a
  > throwaway call and immediately end it) — never ship the `voip` background
  > mode without this plugin's reporting.

- A [VoIP Services certificate / APNs key](https://developer.apple.com/documentation/pushkit)
  on your server to send ring pushes.

### Android setup

- Firebase in the **app module**: `google-services.json` + the
  `com.google.gms.google-services` Gradle plugin. The plugin's manifest
  (services, receiver, full-screen activity, permissions) merges into your
  app automatically.
- Your server sends **high-priority data-only** FCM messages for rings.
- **Exclude the plugin's state file from backups.** Queued call actions
  (and their join tokens) persist in
  `shared_prefs/voip_push_call_state.xml`; if your app allows backups
  (`android:allowBackup="true"`, the default), exclude that file via
  [`android:fullBackupContent` / `dataExtractionRules`](https://developer.android.com/identity/data/autobackup#IncludingFiles),
  or set `android:allowBackup="false"`. Tokens are short-lived and
  age-expired by the plugin, but they should never leave the device.

## JavaScript usage

Use the typed bindings in [`guest-js`](guest-js) (not yet published to npm
— install from the repo or vendor the file):

```ts
import {
  registerForPush,
  drainPendingCallActions,
  endCall,
  onCallAction,
  onVoipToken,
  onFcmToken,
} from 'tauri-plugin-voip-push-api';

// 1. Register (requests notification permission, resolves with tokens).
//    → POST the result to your backend so it can target this device.
const reg = await registerForPush();

// 2. Token rotation → re-register with your backend.
await onVoipToken(({ token }) => {/* iOS PushKit VoIP token */});
await onFcmToken(({ token }) => {/* Android FCM token */});

// 3. Call actions from the native call UI.
await onCallAction(async () => {
  const actions = await drainPendingCallActions();
  // kind: "answer" (carries joinToken) | "decline" | "end"
});
// Also drain once after your app boots — actions queued while the webview
// was dead (cold start) wait for you.

// 4. When a call ends (hangup, answered elsewhere, server cancel), dismiss
//    the native call UI:
await endCall(callId);
```

Or use the core API directly:

```ts
import { invoke, addPluginListener } from '@tauri-apps/api/core';

const reg = await invoke<PushRegistration>('plugin:voip-push|register_for_push');
await addPluginListener('voip-push', 'call_action', async () => {
  const { actions } = await invoke<{ actions: CallAction[] }>(
    'plugin:voip-push|drain_pending_call_actions',
  );
});
await invoke('plugin:voip-push|end_call', { callId });
```

```ts
type PushRegistration = {
  token: string;        // APNs device token (iOS) or FCM token (Android)
  voipToken?: string;   // PushKit VoIP token (iOS only)
  deviceId: string;     // identifierForVendor / ANDROID_ID
  appVersion?: string;
  platform: 'ios' | 'android';
};

type CallAction = {
  kind: 'answer' | 'decline' | 'end';
  callId: string;
  joinToken?: string;   // your signed join token — present on "answer"
  from?: string;        // caller number — present on "answer"
  personName?: string;  // caller display name — present on "answer"
  lineName?: string;    // line/queue name, shown as "Line · number" fallback
};
```

## Push payload contract

Your server triggers rings with these payloads. Payload keys are snake_case
(APNs/FCM convention); the plugin delivers camelCase to your webview. All
FCM data values are strings.

### Ring

iOS — VoIP push (PushKit, `apns-push-type: voip`), flat dictionary;
Android — FCM **data** message, `priority: high`:

| key           | value                                                          | notes |
| ------------- | -------------------------------------------------------------- | ----- |
| `action`      | `"ring"`                                                       |       |
| `call_id`     | your call id (string)                                          |       |
| `join_token`  | signed token the client uses to join the call                  | opaque to the plugin — WebSocket ticket, WebRTC auth, … |
| `from` / `from_number` | caller number                                         | `from` on iOS; **`from_number` on Android** (FCM reserves `from`) |
| `person_name` | caller display name, optional                                  |       |
| `line_name`   | line/queue display name, optional                              | shown as "Line · number" when `person_name` is absent |
| `expires_at`  | ms-epoch ring deadline; the client auto-ends the ring after it |       |
| `answer_url`  | absolute `https`/`http` URL, optional                          | POSTed natively on Answer — see below |

#### Native answer callback

When the user answers on the native call UI and the ring carried a
non-empty `answer_url`, the plugin sends one fire-and-forget request
(~10 s timeout, no retries) straight from native code, so your server
learns about the answer even while the app is locked/backgrounded and the
webview can't run JS yet:

```http
POST <answer_url>
Content-Type: application/json

{"call_id": "<call_id>", "join_token": "<join_token>", "device_id": "<deviceId>"}
```

`device_id` is the same `deviceId` `register_for_push` returns
(`identifierForVendor` on iOS, `ANDROID_ID` on Android). Authenticate the
request with `join_token`. The webview still receives the usual `answer`
action; `answer_url` is not exposed to JS. Omit the key to disable the
callback. On Android, plain `http` needs a cleartext-permitting network
security config in the host app.

### Cancel

Same channels, `action: "cancel"` + `call_id` + `reason`. `reason` picks the
native disconnect cause: anything containing `answer` (e.g.
`"answered_elsewhere"`) shows "answered on another device"; anything else
(`"caller_hung_up"`, `"timeout"`, …) shows a remote hangup. Send it to all
of a user's devices when the ring resolves anywhere.

- **Call answered on this device:** a cancel whose `reason` contains
  `answer` is ignored (it's your fan-out reaching the answering device).
  Any other reason ends the native call and queues an `end` action so the
  webview tears down.
- **Cancel before ring:** APNs/FCM don't guarantee ordering. A cancel for a
  call the device hasn't rung yet leaves a 60 s tombstone; a ring for that
  `call_id` arriving within the window is not shown (iOS reports and
  immediately ends it, as PushKit requires).

## Commands & events

| Command                      | Purpose                                             |
| ---------------------------- | --------------------------------------------------- |
| `register_for_push`          | Permission prompt + token acquisition               |
| `drain_pending_call_actions` | Read-and-clear queued Answer/Decline/End actions    |
| `end_call`                   | Dismiss the native call UI for a `callId`           |

| Event           | Platform | Fired when                                          |
| --------------- | -------- | --------------------------------------------------- |
| `voip_token`    | iOS      | PushKit VoIP token delivered/rotated                |
| `fcm_token`     | Android  | FCM token rotated mid-session                       |
| `call_action`   | both     | A native call action was queued — drain the queue   |
| `audio_session` | iOS      | CallKit activated/deactivated the app audio session |

The two token events stay separate on purpose: iOS carries **two** tokens
(APNs alert + PushKit VoIP) with different destinations on your server,
while Android has one FCM token.

`audio_session` (`{ active: boolean }`) fires from CallKit's
`didActivate` / `didDeactivate` callbacks. The plugin configures the shared
`AVAudioSession` for a voice call (`.playAndRecord` / `.voiceChat`) before
every incoming-call report and on Answer, but never activates it itself —
CallKit does. Start (or resume) your WebRTC / Web Audio graph when
`active` is `true`; a `resume()` issued before that point can hang.

## Security

- **Join tokens.** `join_token` is a bearer credential for the call. The
  plugin never logs it (`toString()` implementations redact it), drops
  un-drained actions after 24 hours, and caps the queue at 50 entries. Keep
  server-side lifetimes short (a ring's lifetime) and single-use.
- **Android at-rest state.** Rings and queued actions persist in
  SharedPreferences so they survive process death. Exclude
  `voip_push_call_state.xml` from backups (see [Android setup](#android-setup)).
- **Capability scoping.** `voip-push:default` grants the full surface. For
  windows that load remote/untrusted content, grant `voip-push:minimal`
  (registration + listeners only) — or nothing.
- **Payload trust.** Ring payloads come from your server via APNs/FCM.
  Malformed or expired pushes are reported to CallKit as throwaway calls
  (Apple's contract) or dropped (Android); no payload data is evaluated or
  rendered as markup.

## Privacy

`register_for_push` returns device identifiers to your webview:
`identifierForVendor` (iOS) and `ANDROID_ID` (Android). Both are app-scoped
and reset on uninstall (iOS) or app-signing/user change (Android). If you
send them to your backend, disclose that in your privacy policy and the
App Store / Play data-safety forms.

## Platform differences & known limitations

- **State durability.** Android persists rings and queued actions in
  SharedPreferences (the FCM service can run and die without the webview
  ever booting). iOS keeps them in memory — a VoIP push always launches the
  app, so memory suffices. If iOS terminates the app *mid-ring* (rare;
  e.g. force-quit), CallKit may still show the call but the answer cannot
  be fulfilled — the ring dies on tap.
- **Ring surfaces.** iOS: CallKit only (a Do-Not-Disturb refusal means no
  ring). Android: Telecom (self-managed) *plus* a CallStyle full-screen
  notification, so the ring survives Telecom refusals; on Android 14+ the
  full-screen surface degrades to a heads-up banner if the user disables
  the special full-screen-intent permission.
- **Lock-screen answer.** iOS does not foreground the app on a lock-screen
  answer; the webview joins when the user opens the app. Android launches
  the app after an answer where background-activity-launch rules allow.
- **CallKit config is fixed** (audio-only, single call group) and the
  Android notification uses a system icon. Configuration hooks are on the
  roadmap.

## How it works

- **iOS** ([ios/Sources/VoipPushPlugin.swift](ios/Sources/VoipPushPlugin.swift)):
  APNs registration callbacks are injected into Tauri's app delegate at
  runtime (`class_addMethod`), a `PKPushRegistry` acquires the VoIP token on
  every launch, and incoming VoIP pushes are reported to CallKit
  synchronously. Call actions queue in memory (a VoIP push always relaunches
  the app), deduped and capped.
- **Android** ([android/src/main/java/com/voippush](android/src/main/java/com/voippush)):
  a `FirebaseMessagingService` wakes on data pushes, reports the call to
  Telecom (self-managed) *and* posts a CallStyle full-screen notification, so
  the ring survives Telecom refusals. Call actions persist in
  SharedPreferences (the FCM process can die before the webview ever boots),
  deduped, capped, and age-expired.

## License

[MIT](LICENSE)
