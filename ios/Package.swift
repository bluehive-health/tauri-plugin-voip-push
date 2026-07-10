// swift-tools-version:5.5

import PackageDescription

let package = Package(
  name: "tauri-plugin-voip-push",
  platforms: [
    .iOS(.v13)
  ],
  products: [
    .library(
      name: "tauri-plugin-voip-push",
      type: .static,
      targets: ["tauri-plugin-voip-push"])
  ],
  dependencies: [
    // Copied in by the tauri-plugin build script (`.ios_path("ios")`).
    .package(name: "Tauri", path: "../.tauri/tauri-api")
  ],
  targets: [
    .target(
      name: "tauri-plugin-voip-push",
      dependencies: [
        .byName(name: "Tauri")
      ],
      path: "Sources")
  ]
)
