use serde::de::DeserializeOwned;
use serde::Serialize;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::{DrainedCallActions, PushRegistration};

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_voip_push);

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EndCallArgs {
    call_id: String,
}

/// Handle to the native plugin class (ios/Sources/VoipPushPlugin.swift or
/// android/src/main/java/com/voippush/VoipPushPlugin.kt).
pub struct VoipPush<R: Runtime>(PluginHandle<R>);

impl<R: Runtime> VoipPush<R> {
    pub fn init<C: DeserializeOwned>(
        _app: &AppHandle<R>,
        api: PluginApi<R, C>,
    ) -> crate::Result<Self> {
        #[cfg(target_os = "ios")]
        let handle = api.register_ios_plugin(init_plugin_voip_push)?;
        #[cfg(target_os = "android")]
        let handle = api.register_android_plugin("com.voippush", "VoipPushPlugin")?;
        Ok(Self(handle))
    }

    pub async fn register_for_push(&self) -> crate::Result<PushRegistration> {
        self.run_blocking::<PushRegistration, _>("registerForPush", ())
            .await
    }

    pub async fn drain_pending_call_actions(&self) -> crate::Result<DrainedCallActions> {
        self.run_blocking::<DrainedCallActions, _>("drainPendingCallActions", ())
            .await
    }

    pub async fn end_call(&self, call_id: String) -> crate::Result<()> {
        self.run_blocking::<(), _>("endCall", EndCallArgs { call_id })
            .await
    }

    /// `run_mobile_plugin` blocks until the native side responds (up to 30s
    /// for APNs token acquisition), so run it on the dedicated blocking pool
    /// instead of tying up one of the async runtime's worker threads.
    async fn run_blocking<T, A>(&self, command: &'static str, args: A) -> crate::Result<T>
    where
        T: DeserializeOwned + Send + 'static,
        A: Serialize + Send + 'static,
    {
        let handle = self.0.clone();
        tauri::async_runtime::spawn_blocking(move || {
            handle
                .run_mobile_plugin::<T>(command, args)
                .map_err(crate::Error::from)
        })
        .await?
    }
}
