// swift-tools-version: 6.3.1
import PackageDescription

// DO NOT MODIFY THIS FILE - managed by Capacitor CLI commands
let package = Package(
    name: "CapApp-SPM",
    platforms: [.iOS(.v18)],
    products: [
        .library(
            name: "CapApp-SPM",
            targets: ["CapApp-SPM"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.3.0"),
        .package(name: "CapacitorCommunitySafeArea", path: "../../../node_modules/@capacitor-community/safe-area"),
        .package(name: "CapacitorApp", path: "../../../node_modules/@capacitor/app"),
        .package(name: "CapacitorKeyboard", path: "../../../node_modules/@capacitor/keyboard"),
        .package(name: "CapacitorPushNotifications", path: "../../../node_modules/@capacitor/push-notifications")
    ],
    targets: [
        .target(
            name: "CapApp-SPM",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "CapacitorCommunitySafeArea", package: "CapacitorCommunitySafeArea"),
                .product(name: "CapacitorApp", package: "CapacitorApp"),
                .product(name: "CapacitorKeyboard", package: "CapacitorKeyboard"),
                .product(name: "CapacitorPushNotifications", package: "CapacitorPushNotifications")
            ]
        )
    ]
)
