/**
 * Typed bindings for tauri-plugin-voip-push.
 *
 * Registration flow:
 *   1. Call {@link registerForPush} at startup (requests notification
 *      permission, resolves with tokens) and POST the result to your
 *      backend so it can target this device.
 *   2. Listen for token rotation ({@link onVoipToken}, {@link onFcmToken})
 *      and re-register when it fires.
 *   3. Listen for native call actions ({@link onCallAction}) and drain the
 *      queue with {@link drainPendingCallActions}. Also drain once after
 *      your app boots — actions queued while the webview was dead (cold
 *      start) wait for you.
 *   4. When a call ends (hangup, answered elsewhere, server cancel), call
 *      {@link endCall} to dismiss the native call UI.
 */
import { addPluginListener, invoke, type PluginListener } from '@tauri-apps/api/core';

const PLUGIN = 'voip-push';

/** Resolved by {@link registerForPush}. */
export interface PushRegistration {
  /** Hex APNs device token (iOS) or FCM registration token (Android). */
  token: string;
  /** Hex PushKit VoIP token — iOS only, may lag the APNs token briefly. */
  voipToken?: string;
  /** iOS `identifierForVendor` / Android `ANDROID_ID` — stable per install. */
  deviceId: string;
  /** App version of the running build. */
  appVersion?: string;
  platform: 'ios' | 'android';
}

export type CallActionKind = 'answer' | 'decline' | 'end';

/** One Answer / Decline / End tap on the native call surface. */
export interface CallAction {
  kind: CallActionKind;
  callId: string;
  /** Signed token your app uses to join the call — present on `answer`. */
  joinToken?: string;
  /** Caller number — present on `answer`. */
  from?: string;
  /** Caller display name — present on `answer`. */
  personName?: string;
  /** Line / queue display name — present on `answer`. */
  lineName?: string;
}

/**
 * Request notification permission, register with APNs/FCM, and resolve with
 * the device token(s). Rejects on desktop with an "unsupported" error the
 * caller can swallow, so the same startup path runs everywhere.
 */
export async function registerForPush(): Promise<PushRegistration> {
  return invoke<PushRegistration>(`plugin:${PLUGIN}|register_for_push`);
}

/**
 * Read-and-clear the queue of call actions made on the native call UI.
 * Returns an empty list on desktop.
 */
export async function drainPendingCallActions(): Promise<CallAction[]> {
  const response = await invoke<{ actions: CallAction[] }>(
    `plugin:${PLUGIN}|drain_pending_call_actions`,
  );
  return response.actions;
}

/**
 * Dismiss the native call UI (CallKit / Telecom) for a call your app
 * reports as over (hangup, answered in-app, server cancel). No-op on
 * desktop.
 */
export async function endCall(callId: string): Promise<void> {
  return invoke(`plugin:${PLUGIN}|end_call`, { callId });
}

/**
 * A native call action was queued — drain the queue. Fires while the
 * webview is alive; actions queued before it booted are picked up by your
 * own post-boot {@link drainPendingCallActions} call.
 */
export async function onCallAction(
  handler: () => void,
): Promise<PluginListener> {
  return addPluginListener(PLUGIN, 'call_action', handler);
}

/** iOS PushKit VoIP token delivered or rotated — re-register your device. */
export async function onVoipToken(
  handler: (event: { token: string }) => void,
): Promise<PluginListener> {
  return addPluginListener(PLUGIN, 'voip_token', handler);
}

/** Android FCM token rotated mid-session — re-register your device. */
export async function onFcmToken(
  handler: (event: { token: string }) => void,
): Promise<PluginListener> {
  return addPluginListener(PLUGIN, 'fcm_token', handler);
}

/**
 * iOS only: CallKit activated (`active: true`) or deactivated the app's
 * audio session. Fires after an Answer and around system interruptions —
 * the right moment to (re)start a WebRTC / Web Audio graph.
 */
export async function onAudioSession(
  handler: (event: { active: boolean }) => void,
): Promise<PluginListener> {
  return addPluginListener(PLUGIN, 'audio_session', handler);
}
