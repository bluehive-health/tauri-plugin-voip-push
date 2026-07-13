use tauri::{command, AppHandle, Runtime, State};

use crate::{DrainedCallActions, PushRegistration, Result, VoipPush};

/// Request notification permission, register with APNs/FCM, and return the
/// device token. `async` is load-bearing: it moves the command off the main
/// thread onto Tauri's async runtime, because the native side needs the
/// main thread to show the permission prompt and receive the APNs delegate
/// callback — a sync command deadlocks the app at startup. The blocking
/// native bridge itself runs on the dedicated blocking pool (see
/// `VoipPush::register_for_push`), so it cannot starve async workers.
#[command]
pub(crate) async fn register_for_push<R: Runtime>(
    _app: AppHandle<R>,
    voip_push: State<'_, VoipPush<R>>,
) -> Result<PushRegistration> {
    voip_push.register_for_push().await
}

/// Read-and-clear the queue of Answer/Decline/End taps made on the native
/// call surfaces. Returns an empty list on desktop.
#[command]
pub(crate) async fn drain_pending_call_actions<R: Runtime>(
    _app: AppHandle<R>,
    voip_push: State<'_, VoipPush<R>>,
) -> Result<DrainedCallActions> {
    voip_push.drain_pending_call_actions().await
}

/// Dismiss the native call UI (CallKit / Telecom) for a call the webview
/// reports as over. No-op on desktop.
#[command]
pub(crate) async fn end_call<R: Runtime>(
    _app: AppHandle<R>,
    voip_push: State<'_, VoipPush<R>>,
    call_id: String,
) -> Result<()> {
    voip_push.end_call(call_id).await
}
