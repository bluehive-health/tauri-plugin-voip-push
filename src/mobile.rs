use serde::de::DeserializeOwned;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::PushRegistration;

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_voip_push);

/// Handle to the native plugin class (ios/Sources/VoipPushPlugin.swift or
/// android/src/main/java/com/bluehive/voippush/VoipPushPlugin.kt).
pub struct VoipPush<R: Runtime>(PluginHandle<R>);

impl<R: Runtime> VoipPush<R> {
    pub fn init<C: DeserializeOwned>(
        _app: &AppHandle<R>,
        api: PluginApi<R, C>,
    ) -> crate::Result<Self> {
        #[cfg(target_os = "ios")]
        let handle = api.register_ios_plugin(init_plugin_voip_push)?;
        #[cfg(target_os = "android")]
        let handle = api.register_android_plugin("com.bluehive.voippush", "VoipPushPlugin")?;
        Ok(Self(handle))
    }

    pub fn register_for_push(&self) -> crate::Result<PushRegistration> {
        self.0
            .run_mobile_plugin("registerForPush", ())
            .map_err(Into::into)
    }
}
