import AVFoundation

/// Captures 24 kHz mono PCM16 audio, chunked for the Realtime API.
///
/// Port of `android/.../audio/PcmAudioRecorder.kt`. The audio pipeline changes
/// completely (`AVAudioEngine` tap instead of `AudioRecord`), but the contract
/// is the same: 24 kHz, mono, 16-bit little-endian PCM delivered in ~100 ms
/// chunks.
///
/// iOS-specific caveats that have no Android equivalent:
/// - The keyboard extension needs **full access** (`RequestOpenAccess`) before
///   the mic works at all, and the host app needs `NSMicrophoneUsageDescription`.
/// - The mic is captured at the hardware rate (usually 48 kHz) and downsampled
///   here to 24 kHz via `AVAudioConverter`.
/// - `onSilencedChanged` has no direct counterpart on iOS; the AVAudioEngine
///   tap simply stops delivering samples. Detect silence in the session
///   interruption/deactivation callbacks instead.
final class PcmAudioRecorder {
    static let sampleRate = 24_000.0
    static let chunkMilliseconds = 100
    static let bytesPerSample = 2
    static let chunkBytes = Int(sampleRate) * chunkMilliseconds / 1_000 * bytesPerSample

    typealias OnAudio = (Data) -> Void
    typealias OnError = (String) -> Void

    private let onAudio: OnAudio
    private let onError: OnError

    private let engine = AVAudioEngine()
    private let queue = DispatchQueue(label: "dev.dobrinskiy.livetype.audio")

    private var running = false

    init(onAudio: @escaping OnAudio, onError: @escaping OnError) {
        self.onAudio = onAudio
        self.onError = onError
    }

    func start() throws {
        guard !running else { throw RecorderError.alreadyRunning }

        let input = engine.inputNode
        let hardwareFormat = input.outputFormat(forBus: 0)
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Self.sampleRate,
            channels: 1,
            interleaved: true
        ) else {
            throw RecorderError.unsupportedFormat
        }
        guard let converter = AVAudioConverter(from: hardwareFormat, to: targetFormat) else {
            throw RecorderError.unsupportedFormat
        }

        let chunkFrames = AVAudioFrameCount(hardwareFormat.sampleRate * Double(Self.chunkMilliseconds) / 1_000)
        let onAudio = self.onAudio
        let onError = self.onError

        input.installTap(onBus: 0, bufferSize: chunkFrames, format: hardwareFormat) { buffer, _ in
            guard let output = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: buffer.frameLength) else {
                return
            }
            let inputBlock: AVAudioConverterInputBlock = { _, outStatus in
                outStatus.pointee = buffer.frameLength > 0 ? .haveData : .noDataNow
                return buffer
            }
            var error: NSError?
            converter.convert(to: output, error: &error, withInputFrom: inputBlock)
            if let error {
                onError("Audio conversion failed: \(error.localizedDescription)")
                return
            }
            guard let int16 = output.int16ChannelData else { return }
            let bytes = Data(
                bytes: int16[0],
                count: Int(output.frameLength) * Self.bytesPerSample
            )
            onAudio(bytes)
        }

        engine.prepare()
        try engine.start()
        running = true
    }

    func stop() {
        guard running else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
    }

    var isRecording: Bool { running }

    enum RecorderError: LocalizedError {
        case alreadyRunning
        case unsupportedFormat

        var errorDescription: String? {
            switch self {
            case .alreadyRunning: return "Recorder is already running"
            case .unsupportedFormat: return "24 kHz PCM recording is not supported on this device"
            }
        }
    }
}
