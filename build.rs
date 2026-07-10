const COMMANDS: &[&str] = &[
    "register_for_push",
    // VoIP + native call bridge. `register_listener`/`remove_listener`
    // back the webview's `addPluginListener` (voip_token / fcm_token /
    // call_action events); the other two are direct native commands.
    "register_listener",
    "remove_listener",
    "drain_pending_call_actions",
    "end_callkit_call",
];

fn main() {
    // Native source changes must retrigger the build (swift-rs compiles the
    // ios/ package into the Rust staticlib on iOS targets; the android/
    // library project is linked into the app's Gradle build by the Tauri CLI).
    println!("cargo:rerun-if-changed=ios/Sources/VoipPushPlugin.swift");
    println!("cargo:rerun-if-changed=ios/Package.swift");
    println!("cargo:rerun-if-changed=android/src/main");

    tauri_plugin::Builder::new(COMMANDS)
        .ios_path("ios")
        .android_path("android")
        .build();
}
