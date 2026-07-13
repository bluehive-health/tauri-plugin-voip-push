use std::marker::PhantomData;

use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::{DrainedCallActions, PushRegistration};

/// Non-mobile stand-in: `register_for_push` always reports "unsupported" so
/// the webview can call the command unconditionally and swallow the error;
/// the call-bridge commands are safe no-ops so one code path runs
/// everywhere.
pub struct VoipPush<R: Runtime>(PhantomData<fn() -> R>);

impl<R: Runtime> VoipPush<R> {
    pub fn init<C: DeserializeOwned>(
        _app: &AppHandle<R>,
        _api: PluginApi<R, C>,
    ) -> crate::Result<Self> {
        Ok(Self(PhantomData))
    }

    pub async fn register_for_push(&self) -> crate::Result<PushRegistration> {
        Err(crate::Error::UnsupportedPlatform)
    }

    pub async fn drain_pending_call_actions(&self) -> crate::Result<DrainedCallActions> {
        Ok(DrainedCallActions::default())
    }

    pub async fn end_call(&self, _call_id: String) -> crate::Result<()> {
        Ok(())
    }
}
