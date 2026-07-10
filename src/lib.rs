//! APNs/FCM push-token acquisition + native incoming-call bridge.
//!
//! The webview invokes `plugin:voip-push|register_for_push`, which (on iOS
//! and Android) requests notification permission, registers with APNs/FCM,
//! and resolves with the device token + a stable device id. The app then
//! sends the payload to its own backend to register the device for pushes.
//!
//! On desktop the command returns an "unsupported" error that the caller
//! can swallow, so the same startup path runs everywhere.

use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

mod commands;
mod error;

pub use error::{Error, Result};

#[cfg(any(target_os = "ios", target_os = "android"))]
mod mobile;
#[cfg(not(any(target_os = "ios", target_os = "android")))]
mod unsupported;

#[cfg(any(target_os = "ios", target_os = "android"))]
use mobile::VoipPush;
#[cfg(not(any(target_os = "ios", target_os = "android")))]
use unsupported::VoipPush;

/// What the native side resolves `registerForPush` with. Serialized to the
/// webview as camelCase (`token`, `deviceId`, `appVersion`, `platform`).
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushRegistration {
    /// Hex APNs device token (iOS) or FCM registration token (Android).
    pub token: String,
    /// Hex PushKit VoIP token (iOS) — where CallKit ring pushes go. Empty /
    /// absent until Apple delivers it, and always absent on Android.
    #[serde(default)]
    pub voip_token: Option<String>,
    /// iOS `identifierForVendor` / Android `ANDROID_ID` — stable per install.
    pub device_id: String,
    /// App version of the running build.
    #[serde(default)]
    pub app_version: Option<String>,
    /// `"ios"` or `"android"`.
    pub platform: String,
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("voip-push")
        .invoke_handler(tauri::generate_handler![commands::register_for_push])
        .setup(|app, api| {
            let voip_push = VoipPush::init(app, api)?;
            app.manage(voip_push);
            Ok(())
        })
        .build()
}
