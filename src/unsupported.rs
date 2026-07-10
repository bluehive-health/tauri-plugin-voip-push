use std::marker::PhantomData;

use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::PushRegistration;

/// Non-mobile stand-in: `register_for_push` always reports "unsupported" so
/// the webview can call the command unconditionally and swallow the error.
pub struct VoipPush<R: Runtime>(PhantomData<fn() -> R>);

impl<R: Runtime> VoipPush<R> {
    pub fn init<C: DeserializeOwned>(
        _app: &AppHandle<R>,
        _api: PluginApi<R, C>,
    ) -> crate::Result<Self> {
        Ok(Self(PhantomData))
    }

    pub fn register_for_push(&self) -> crate::Result<PushRegistration> {
        Err(crate::Error::UnsupportedPlatform)
    }
}
