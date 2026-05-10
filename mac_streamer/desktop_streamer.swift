import Foundation
import CoreGraphics
import ImageIO
import Network
import UniformTypeIdentifiers

final class DesktopStreamer {
    private let port: UInt16
    private let fps: Double
    private let maxFrameDimension: Int
    private let jpegQuality: Double
    private let queue = DispatchQueue(label: "com.gamesinvr.desktopstreamer")
    private var listener: NWListener?
    private var connection: NWConnection?
    private var timer: DispatchSourceTimer?
    private var sentFrameCount = 0

    init(port: UInt16 = 7010, fps: Double = 6.0, maxFrameDimension: Int = 1280, jpegQuality: Double = 0.7) {
        self.port = port
        self.fps = fps
        self.maxFrameDimension = maxFrameDimension
        self.jpegQuality = jpegQuality
    }

    func start() throws {
        let listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!)
        listener.newConnectionHandler = { [weak self] newConnection in
            self?.handleConnection(newConnection)
        }
        listener.stateUpdateHandler = { state in
            print("Desktop streamer listener state: \(state)")
        }
        listener.start(queue: queue)
        self.listener = listener

        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(200), repeating: .milliseconds(Int(1000.0 / fps)))
        timer.setEventHandler { [weak self] in
            self?.captureAndSendFrame()
        }
        timer.resume()
        self.timer = timer

        print("Desktop streamer listening on tcp://0.0.0.0:\(port)")
        dispatchMain()
    }

    private func handleConnection(_ newConnection: NWConnection) {
        print("Desktop streamer accepted client \(newConnection.endpoint)")
        connection?.cancel()
        connection = newConnection
        newConnection.stateUpdateHandler = { state in
            print("Desktop streamer client state: \(state)")
        }
        newConnection.start(queue: queue)
    }

    private func captureAndSendFrame() {
        guard let connection else {
            return
        }
        let tempUrl = FileManager.default.temporaryDirectory
            .appendingPathComponent("quest-headpose-stream-\(UUID().uuidString).jpg")
        let capture = Process()
        capture.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        capture.arguments = ["-x", "-t", "jpg", tempUrl.path]
        do {
            try capture.run()
            capture.waitUntilExit()
        } catch {
            print("Desktop streamer capture launch failed: \(error)")
            return
        }
        guard
            capture.terminationStatus == 0,
            let jpegData = try? Data(contentsOf: tempUrl),
            let imageSource = CGImageSourceCreateWithData(jpegData as CFData, nil),
            let scaledFrame = makeScaledJpegFrame(from: imageSource)
        else {
            if capture.terminationStatus != 0 {
                print("Desktop streamer capture failed with status \(capture.terminationStatus)")
            }
            try? FileManager.default.removeItem(at: tempUrl)
            return
        }
        try? FileManager.default.removeItem(at: tempUrl)

        var header = Data()
        header.append(contentsOf: withUnsafeBytes(of: UInt32(scaledFrame.width).bigEndian, Array.init))
        header.append(contentsOf: withUnsafeBytes(of: UInt32(scaledFrame.height).bigEndian, Array.init))
        header.append(contentsOf: withUnsafeBytes(of: UInt32(scaledFrame.jpegData.count).bigEndian, Array.init))

        var packet = Data()
        packet.append(header)
        packet.append(scaledFrame.jpegData)

        connection.send(content: packet, completion: .contentProcessed { error in
            if let error {
                print("Desktop streamer send error: \(error)")
                self.connection = nil
            } else {
                self.sentFrameCount += 1
                if self.sentFrameCount == 1 || self.sentFrameCount % 60 == 0 {
                    print(
                        "Desktop streamer sent frame \(self.sentFrameCount) " +
                        "(\(scaledFrame.width)x\(scaledFrame.height), \(scaledFrame.jpegData.count) bytes)"
                    )
                }
            }
        })
    }

    private func makeScaledJpegFrame(from imageSource: CGImageSource) -> (jpegData: Data, width: Int, height: Int)? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxFrameDimension,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, options as CFDictionary) else {
            return nil
        }

        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        let properties: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: jpegQuality,
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else {
            return nil
        }
        return (output as Data, image.width, image.height)
    }
}

do {
    try DesktopStreamer().start()
} catch {
    fputs("Desktop streamer failed: \(error)\n", stderr)
    exit(1)
}
