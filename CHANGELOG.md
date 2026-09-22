# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Native answer callback: ring pushes may carry an optional `answer_url`.
  When the user answers on the native call UI, the plugin POSTs
  `{ call_id, join_token, device_id }` to it directly (iOS inside a
  background task), so the server learns of a lock-screen answer without
  waiting for the webview. Absent/empty `answer_url` keeps the old behavior.
- Cancel-before-ring tombstones: a cancel for a call not yet rung
  suppresses a matching ring that arrives within 60 seconds.
- iOS: the shared `AVAudioSession` is configured for a voice call
  (`.playAndRecord` / `.voiceChat`, Bluetooth allowed) before every
  `reportNewIncomingCall` and on Answer, and CallKit's
  `didActivate` / `didDeactivate` callbacks are forwarded to the webview as
  the `audio_session` event (`{ active: boolean }`; `onAudioSession` in
  `guest-js`) so WebRTC audio can start the moment the session is live.
- Initial release: APNs/FCM push-token acquisition (`register_for_push`),
  native incoming-call UI (CallKit on iOS, self-managed Telecom +
  CallStyle notification on Android), and a durable Answer/Decline/End
  action bridge to the webview (`drain_pending_call_actions`, `end_call`).
- Typed JavaScript bindings in `guest-js` (`tauri-plugin-voip-push-api`).
- `voip-push:minimal` permission set for windows that load remote content
  (token registration + rotation events only).
- Rust command handlers for the full command surface; the blocking native
  bridge runs on the async runtime's blocking pool.

### Changed

- A cancel for a call answered on this device is only ignored when its
  `reason` contains `answer`; any other reason (caller hung up, ring timed
  out) now ends the native call and queues an `end` action for the webview.

### Security

- Pending call actions are deduped, capped at 50, and expire after 24
  hours on both platforms, so join tokens cannot accumulate indefinitely.
- Durability-critical Android writes (`saveRing`, `markAnswered`,
  `enqueueAction`) use synchronous `commit()` so an Answer tap survives
  immediate process death.
