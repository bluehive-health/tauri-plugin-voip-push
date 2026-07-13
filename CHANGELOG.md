# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Initial release: APNs/FCM push-token acquisition (`register_for_push`),
  native incoming-call UI (CallKit on iOS, self-managed Telecom +
  CallStyle notification on Android), and a durable Answer/Decline/End
  action bridge to the webview (`drain_pending_call_actions`, `end_call`).
- Typed JavaScript bindings in `guest-js` (`tauri-plugin-voip-push-api`).
- `voip-push:minimal` permission set for windows that load remote content
  (token registration + rotation events only).
- Rust command handlers for the full command surface; the blocking native
  bridge runs on the async runtime's blocking pool.

### Security

- Pending call actions are deduped, capped at 50, and expire after 24
  hours on both platforms, so join tokens cannot accumulate indefinitely.
- Durability-critical Android writes (`saveRing`, `markAnswered`,
  `enqueueAction`) use synchronous `commit()` so an Answer tap survives
  immediate process death.
