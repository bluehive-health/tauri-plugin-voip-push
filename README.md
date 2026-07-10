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

`src-tauri/Cargo.toml`:

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

## JavaScript usage

There is no JS wrapper package (yet) — use the core API directly:

```ts
import { invoke } from '@tauri-apps/api/core';
import { addPluginListener } from '@tauri-apps/api/core';

// 1. Register (requests notification permission, resolves with tokens).
const reg = await invoke<{
  token: string;        // APNs device token (iOS) or FCM token (Android)
  voipToken?: string;   // PushKit VoIP token (iOS only)
  deviceId: string;     // identifierForVendor / ANDROID_ID
  appVersion?: string;
  platform: 'ios' | 'android';
}>('plugin:voip-push|register_for_push');
// → POST these to your backend so it can target this device.

// 2. Token rotation events.
await addPluginListener('voip-push', 'voip_token', ({ token }) => { /* re-register */ });
await addPluginListener('voip-push', 'fcm_token', ({ token }) => { /* re-register */ });

// 3. Call actions from the native call UI.
await addPluginListener('voip-push', 'call_action', async () => {
  const { actions } = await invoke<{ actions: CallAction[] }>(
    'plugin:voip-push|drain_pending_call_actions',
  );
  // kind: "answer" (carries wsToken) | "decline" | "end"
});
// Also drain once after your app boots — actions queued while the webview
// was dead (cold start) wait for you.

// 4. When a call ends (hangup, answered elsewhere, server cancel), dismiss
//    the native call UI:
await invoke('plugin:voip-push|end_callkit_call', { callId });
```

```ts
type CallAction = {
  kind: 'answer' | 'decline' | 'end';
  callId: string;
  wsToken: string;   // your signed join token — present on "answer"
  from: string;      // caller number
  personName: string;
  flowName: string;  // line/queue name, shown as "Flow · number" fallback
};
```

## Push payload contract

Your server triggers rings with these payloads. String values only on FCM
(all FCM data values are strings).

### Ring

iOS — VoIP push (PushKit, `apns-push-type: voip`), flat dictionary;
Android — FCM **data** message, `priority: high`:

| key           | value                                                        |
| ------------- | ------------------------------------------------------------ |
| `action`      | `"ring"`                                                     |
| `call_id`     | your call id (string)                                        |
| `ws_token`    | signed token the client uses to join the call                |
| `from` (iOS) / `from_number` (Android) | caller number (FCM reserves `from`) |
| `person_name` | caller display name, optional                                |
| `flow_name`   | line/queue display name, optional                            |
| `reason`      | empty on ring                                                |
| `expires_at`  | ms-epoch ring deadline; the client auto-ends the ring after it |

### Cancel

Same channels, `action: "cancel"` + `call_id` + `reason`
(`"answered_elsewhere"`, `"caller_hung_up"`, `"timeout"`, …). Send it to all
of a user's devices when the ring resolves anywhere.

## Commands & events

| Command                      | Purpose                                             |
| ---------------------------- | --------------------------------------------------- |
| `register_for_push`          | Permission prompt + token acquisition               |
| `drain_pending_call_actions` | Read-and-clear queued Answer/Decline/End actions    |
| `end_callkit_call`           | Dismiss the native call UI for a `callId` (both platforms; name kept for compat) |

| Event         | Fired when                                        |
| ------------- | ------------------------------------------------- |
| `voip_token`  | iOS PushKit VoIP token delivered/rotated          |
| `fcm_token`   | Android FCM token rotated mid-session             |
| `call_action` | A native call action was queued — drain the queue |

## How it works

- **iOS** ([ios/Sources/VoipPushPlugin.swift](ios/Sources/VoipPushPlugin.swift)):
  APNs registration callbacks are injected into Tauri's app delegate at
  runtime (`class_addMethod`), a `PKPushRegistry` acquires the VoIP token on
  every launch, and incoming VoIP pushes are reported to CallKit
  synchronously. Call actions queue in memory (a VoIP push always relaunches
  the app).
- **Android** ([android/src/main/java/com/bluehive/voippush](android/src/main/java/com/bluehive/voippush)):
  a `FirebaseMessagingService` wakes on data pushes, reports the call to
  Telecom (self-managed) *and* posts a CallStyle full-screen notification, so
  the ring survives Telecom refusals. Call actions persist in
  SharedPreferences (the FCM process can die before the webview ever boots).

## License

[MIT](LICENSE)
