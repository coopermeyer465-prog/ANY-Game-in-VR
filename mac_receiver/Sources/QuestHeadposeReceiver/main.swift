import ApplicationServices
import Darwin
import Foundation

private typealias CursorVisibilityFunction = @convention(c) () -> Int32

private let dynamicCursorVisibility: CursorVisibilityFunction? = {
    guard
        let handle = dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", RTLD_LAZY),
        let symbol = dlsym(handle, "CGCursorIsVisible")
    else {
        return nil
    }
    return unsafeBitCast(symbol, to: CursorVisibilityFunction.self)
}()

private func isCursorVisible() -> Bool {
    dynamicCursorVisibility?() != 0
}

private var activeReceiver: Receiver?

struct ReceiverConfig {
    let listenPort: UInt16
    let defaultQuestPort: UInt16
    let sensitivity: Double
    let detectedQuestIP: String
    let macIP: String

    static func load(from path: String) throws -> ReceiverConfig {
        let raw = try String(contentsOfFile: path, encoding: .utf8)
        var values: [String: String] = [:]
        raw.split(separator: "\n").forEach { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty || trimmed.hasPrefix("#") {
                return
            }
            let parts = trimmed.split(separator: "=", maxSplits: 1).map(String.init)
            if parts.count == 2 {
                values[parts[0]] = parts[1]
            }
        }

        return ReceiverConfig(
            listenPort: UInt16(values["LISTEN_PORT"] ?? "7007") ?? 7007,
            defaultQuestPort: UInt16(values["QUEST_PORT"] ?? "5555") ?? 5555,
            sensitivity: Double(values["SENSITIVITY"] ?? "18.0") ?? 18.0,
            detectedQuestIP: values["QUEST_IP"] ?? "",
            macIP: values["MAC_IP"] ?? "",
        )
    }
}

struct ReceiverStatus: Codable {
    let type: String
    let receiverRunning: Bool
    let cursorVisible: Bool
    let sensitivity: Double
    let macIp: String
    let message: String
}

final class MouseInjector {
    func inject(deltaX: Int32, deltaY: Int32) {
        guard deltaX != 0 || deltaY != 0 else {
            return
        }

        guard let event = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: .zero, mouseButton: .left) else {
            return
        }

        event.setIntegerValueField(.mouseEventDeltaX, value: Int64(deltaX))
        event.setIntegerValueField(.mouseEventDeltaY, value: Int64(deltaY))
        event.post(tap: .cghidEventTap)
    }
}

final class Receiver {
    private let configPath: String
    private var config: ReceiverConfig
    private let injector = MouseInjector()
    private var socketFD: Int32 = -1
    private var questAddress: sockaddr_in?
    private var receiverStart = Date()
    private var reloadRequested = false

    init(configPath: String) throws {
        self.configPath = configPath
        self.config = try ReceiverConfig.load(from: configPath)
    }

    func run() throws {
        activeReceiver = self
        try openSocket()
        installSignalHandlers()
        print("Receiver listening on UDP \(config.listenPort)")
        if !config.macIP.isEmpty {
            print("Configured Mac IP: \(config.macIP)")
        }
        if !config.detectedQuestIP.isEmpty {
            print("Configured Quest IP: \(config.detectedQuestIP)")
        }
        print("Sensitivity: \(config.sensitivity)")
        print("Mouse events only inject while the macOS cursor is hidden.")
        print("Accessibility permission is required for synthetic mouse movement.")

        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            if reloadRequested {
                config = try ReceiverConfig.load(from: configPath)
                reloadRequested = false
                print("Reloaded sensitivity: \(config.sensitivity)")
            }

            var peer = sockaddr_in()
            var peerLength = socklen_t(MemoryLayout<sockaddr_in>.size)
            let readCount = withUnsafeMutablePointer(to: &peer) { peerPtr -> Int in
                peerPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                    recvfrom(socketFD, &buffer, buffer.count, 0, sockPtr, &peerLength)
                }
            }

            if readCount <= 0 {
                continue
            }

            questAddress = peer
            let payload = Data(buffer.prefix(readCount))
            try handle(payload: payload)
        }
    }

    private func openSocket() throws {
        socketFD = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFD >= 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.stride)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = config.listenPort.bigEndian
        address.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)

        let bindResult = withUnsafePointer(to: &address) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPointer in
                bind(socketFD, sockPointer, socklen_t(MemoryLayout<sockaddr_in>.stride))
            }
        }

        guard bindResult == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
    }

    private func installSignalHandlers() {
        signal(SIGINT) { _ in exit(0) }
        signal(SIGTERM) { _ in exit(0) }
        signal(SIGHUP) { _ in
            activeReceiver?.requestReload()
        }
    }

    func requestReload() {
        reloadRequested = true
    }

    private func handle(payload: Data) throws {
        guard
            let json = try JSONSerialization.jsonObject(with: payload) as? [String: Any],
            let type = json["type"] as? String
        else {
            return
        }

        switch type {
        case "hello":
            print("Quest hello from \(json["questIp"] ?? "unknown")")
            try sendStatus(message: "Receiver ready")

        case "disconnect":
            print("Quest disconnected: \(json["reason"] ?? "unknown")")
            try sendStatus(message: "Receiver saw disconnect")

        case "headpose":
            try handleHeadpose(json)

        default:
            break
        }
    }

    private func handleHeadpose(_ json: [String: Any]) throws {
        let yawDelta = (json["yawDelta"] as? NSNumber)?.doubleValue ?? 0
        let pitchDelta = (json["pitchDelta"] as? NSNumber)?.doubleValue ?? 0
        let cursorVisible = isCursorVisible()

        if !cursorVisible {
            let dx = Int32((yawDelta * config.sensitivity).rounded())
            let dy = Int32((-pitchDelta * config.sensitivity).rounded())
            injector.inject(deltaX: dx, deltaY: dy)
        }

        if let questIp = json["questIp"] as? String, !questIp.isEmpty {
            let yaw = (json["yaw"] as? NSNumber)?.doubleValue ?? 0
            let pitch = (json["pitch"] as? NSNumber)?.doubleValue ?? 0
            let mode = (json["mode"] as? String) ?? "window"
            let uptime = Int(Date().timeIntervalSince(receiverStart))
            let gate = cursorVisible ? "blocked(cursor visible)" : "injecting"
            print("[\(uptime)s] \(questIp) mode=\(mode) yaw=\(String(format: "%.2f", yaw)) pitch=\(String(format: "%.2f", pitch)) \(gate)")
        }

        try sendStatus(message: cursorVisible ? "Cursor visible, injection paused" : "Injecting hidden-cursor motion")
    }

    private func sendStatus(message: String) throws {
        guard var peer = questAddress else {
            return
        }

        let status = ReceiverStatus(
            type: "status",
            receiverRunning: true,
            cursorVisible: isCursorVisible(),
            sensitivity: config.sensitivity,
            macIp: config.macIP,
            message: message,
        )
        let payload = try JSONEncoder().encode(status)
        let sendResult = payload.withUnsafeBytes { bytes -> Int in
            guard let baseAddress = bytes.baseAddress else {
                return -1
            }
            return withUnsafePointer(to: &peer) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPointer in
                    sendto(socketFD, baseAddress, bytes.count, 0, sockPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }

        if sendResult < 0 {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
    }
}

func sendControlPacket(host: String, port: UInt16, type: String) throws {
    let socketFD = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
    guard socketFD >= 0 else {
        throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
    }
    defer { close(socketFD) }

    var address = sockaddr_in()
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = port.bigEndian
    inet_pton(AF_INET, host, &address.sin_addr)

    let data = try JSONSerialization.data(withJSONObject: [
        "type": type,
        "tsMs": Int(Date().timeIntervalSince1970 * 1000),
    ])

    let result = data.withUnsafeBytes { bytes -> Int in
        guard let baseAddress = bytes.baseAddress else {
            return -1
        }
        return withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPointer in
                sendto(socketFD, baseAddress, bytes.count, 0, sockPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
    }

    if result < 0 {
        throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
    }
}

enum CLIError: Error, CustomStringConvertible {
    case usage

    var description: String {
        "Usage: quest-headpose-receiver run <config-path> | send-control <host> <port> <type>"
    }
}

do {
    let arguments = Array(CommandLine.arguments.dropFirst())
    guard let command = arguments.first else {
        throw CLIError.usage
    }

    switch command {
    case "run":
        guard arguments.count == 2 else {
            throw CLIError.usage
        }
        let receiver = try Receiver(configPath: arguments[1])
        try receiver.run()

    case "send-control":
        guard arguments.count == 4, let port = UInt16(arguments[2]) else {
            throw CLIError.usage
        }
        try sendControlPacket(host: arguments[1], port: port, type: arguments[3])

    default:
        throw CLIError.usage
    }
} catch {
    fputs("\(error)\n", stderr)
    exit(1)
}
