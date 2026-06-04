//
//  MBAudioRecorder.swift
//  MiniCPM-V-demo
//
//  Simple wrapper around AVAudioRecorder for 16kHz mono 16-bit PCM recording.
//  Used by TtsViewController for voice cloning reference audio capture.
//

import Foundation
import AVFoundation

class MBAudioRecorder: NSObject {

    private var recorder: AVAudioRecorder?

    /// Whether recording is currently active.
    var isRecording: Bool {
        return recorder?.isRecording ?? false
    }

    /// Whether microphone permission has been granted.
    var hasPermission: Bool {
        return AVAudioSession.sharedInstance().recordPermission == .granted
    }

    /// Request microphone permission.
    func requestPermission(completion: @escaping (Bool) -> Void) {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            completion(true)
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                DispatchQueue.main.async { completion(granted) }
            }
        default:
            completion(false)
        }
    }

    /// Start recording to the given file URL.
    /// Format: 16kHz mono 16-bit PCM WAV.
    func startRecording(to url: URL) -> Bool {
        guard hasPermission else { return false }

        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                   withIntermediateDirectories: true)

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
        } catch {
            print("MBAudioRecorder: Failed to configure audio session: \(error)")
            return false
        }

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsFloatKey: false,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]

        do {
            let r = try AVAudioRecorder(url: url, settings: settings)
            r.isMeteringEnabled = false
            r.prepareToRecord()
            if !r.record() {
                print("MBAudioRecorder: record() returned false")
                return false
            }
            self.recorder = r
            return true
        } catch {
            print("MBAudioRecorder: Failed to start recording: \(error)")
            return false
        }
    }

    /// Stop recording. Returns the file size in bytes.
    func stopRecording() -> Int64 {
        recorder?.stop()
        recorder = nil

        try? AVAudioSession.sharedInstance().setActive(false)
        return 0
    }

    /// Get duration of a WAV file in milliseconds.
    func getDurationMs(_ fileURL: URL) -> Int {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return 0 }

        do {
            let file = try AVAudioFile(forReading: fileURL)
            let sampleCount = file.length
            let sampleRate = file.fileFormat.sampleRate
            return Int(Double(sampleCount) / sampleRate * 1000.0)
        } catch {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
               let size = attrs[.size] as? Int64 {
                return Int(size * 1000 / 32000)
            }
            return 0
        }
    }
}
