use tauri::{command, AppHandle, Runtime, State};

use crate::{PushRegistration, Result, VoipPush};

/// Request notification permission, register with APNs/FCM, and return the
/// device token. `async` is load-bearing: it moves the command off the main
/// thread onto Tauri's async runtime, because `run_mobile_plugin` blocks
/// until the native side responds — and the native side needs the main
/// thread to show the permission prompt and receive the APNs delegate
/// callback. A sync command deadlocks the app at startup.
#[command]
pub(crate) async fn register_for_push<R: Runtime>(
    _app: AppHandle<R>,
    voip_push: State<'_, VoipPush<R>>,
) -> Result<PushRegistration> {
    voip_push.register_for_push()
}
