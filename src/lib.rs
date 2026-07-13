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

/// One Answer / Decline / End tap on the native call surface, queued until
/// the webview drains it via `drain_pending_call_actions`.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CallAction {
    /// `"answer"` | `"decline"` (un-answered dismiss) | `"end"` (answered hangup).
    pub kind: String,
    pub call_id: String,
    /// Signed token the app uses to join the call — present on `"answer"`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub join_token: Option<String>,
    /// Caller number.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from: Option<String>,
    /// Caller display name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub person_name: Option<String>,
    /// Line / queue display name, shown as "Line · number" fallback.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub line_name: Option<String>,
}

/// Response of `drain_pending_call_actions`.
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DrainedCallActions {
    pub actions: Vec<CallAction>,
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("voip-push")
        .invoke_handler(tauri::generate_handler![
            commands::register_for_push,
            commands::drain_pending_call_actions,
            commands::end_call,
        ])
        .setup(|app, api| {
            let voip_push = VoipPush::init(app, api)?;
            app.manage(voip_push);
            Ok(())
        })
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_registration_serializes_camel_case() {
        let registration = PushRegistration {
            token: "abc".into(),
            voip_token: Some("def".into()),
            device_id: "device".into(),
            app_version: Some("1.0.0".into()),
            platform: "ios".into(),
        };
        let json = serde_json::to_value(&registration).unwrap();
        assert_eq!(json["token"], "abc");
        assert_eq!(json["voipToken"], "def");
        assert_eq!(json["deviceId"], "device");
        assert_eq!(json["appVersion"], "1.0.0");
        assert_eq!(json["platform"], "ios");
    }

    #[test]
    fn call_action_omits_absent_answer_fields() {
        let action = CallAction {
            kind: "decline".into(),
            call_id: "call-1".into(),
            join_token: None,
            from: None,
            person_name: None,
            line_name: None,
        };
        let json = serde_json::to_value(&action).unwrap();
        assert_eq!(json["kind"], "decline");
        assert_eq!(json["callId"], "call-1");
        assert!(json.get("joinToken").is_none());
        assert!(json.get("lineName").is_none());
    }

    #[test]
    fn call_action_round_trips_native_payload() {
        let native = r#"{"kind":"answer","callId":"call-2","joinToken":"tok","from":"+15550100","personName":"Ada","lineName":"Support"}"#;
        let action: CallAction = serde_json::from_str(native).unwrap();
        assert_eq!(action.kind, "answer");
        assert_eq!(action.join_token.as_deref(), Some("tok"));
        assert_eq!(action.line_name.as_deref(), Some("Support"));
    }
}
