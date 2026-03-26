// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "QuestHeadposeReceiver",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(name: "quest-headpose-receiver", targets: ["QuestHeadposeReceiver"]),
    ],
    targets: [
        .executableTarget(
            name: "QuestHeadposeReceiver",
            path: "Sources/QuestHeadposeReceiver",
        ),
    ],
)
