//
//  TtsEngine.swift
//  MiniCPM-V-demo
//
//  VoxCPM2 TTS engine — state-machine driven wrapper over TtsBridge.
//  Singleton, @MainActor, ObservableObject. Mirrors Android's TtsEngine.kt.
//

import Foundation
import Combine

/// TTS engine state machine
public enum TtsState {
    case uninitialized
    case loadingModel
    case ready
    case generating
    case error(Error)
}

/// VoxCPM2 TTS engine singleton.
/// - All native calls are dispatched to a serial background queue to avoid
///   concurrent generation attempts.
/// - State is published on @MainActor so UI can bind to it.
@MainActor
public class TtsEngine: ObservableObject {

    // MARK: - Singleton

    @MainActor public static let shared = TtsEngine()

    private init() {}

    // MARK: - Published state

    @Published public var state: TtsState = .uninitialized

    // MARK: - Private

    /// Serial queue prevents concurrent init/generate calls into the native runtime.
    private let serialQueue = DispatchQueue(label: "com.minicpm.tts.engine", qos: .userInitiated)

    /// Flag to track if native runtime is initialized.
    private var isInitialized = false

    /// Current generation task — for cancellation.
    private var generateTask: Task<Void, Never>?

    // MARK: - Model paths

    private func documentsDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    public func baseLMPath() -> String {
        documentsDirectory()
            .appendingPathComponent(MiniCPMModelConst.voxcpm2_BaseLMFileName)
            .path
    }

    public func acousticPath() -> String {
        documentsDirectory()
            .appendingPathComponent(MiniCPMModelConst.voxcpm2_AcousticFileName)
            .path
    }

    public func modelsExist() -> Bool {
        let fm = FileManager.default
        return fm.fileExists(atPath: baseLMPath()) &&
               fm.fileExists(atPath: acousticPath())
    }

    // MARK: - Public API

    /// Load the VoxCPM2 model (BaseLM + Acoustic GGUFs).
    /// Must be called before `generate`.
    /// - Returns: `true` on success.
    public func loadModel() async -> Bool {
        state = .loadingModel
        isInitialized = false

        return await withCheckedContinuation { continuation in
            serialQueue.async { [weak self] in
                guard let self = self else {
                    continuation.resume(returning: false)
                    return
                }

                let basePath = self.baseLMPath()
                let acPath = self.acousticPath()

                guard FileManager.default.fileExists(atPath: basePath),
                      FileManager.default.fileExists(atPath: acPath) else {
                    Task { @MainActor in
                        self.state = .error(NSError(domain: "TtsEngine",
                                                     code: -1,
                                                     userInfo: [NSLocalizedDescriptionKey: "Model files not found. Please download VoxCPM2 models first."]))
                    }
                    continuation.resume(returning: false)
                    return
                }

                let success = tts_init(basePath, acPath)
                Task { @MainActor in
                    self.isInitialized = success
                    self.state = success ? .ready : .error(NSError(domain: "TtsEngine",
                                                                     code: -2,
                                                                     userInfo: [NSLocalizedDescriptionKey: "Failed to initialize VoxCPM2 runtime."]))
                }
                continuation.resume(returning: success)
            }
        }
    }

    /// Generate speech and write to output_path.
    ///
    /// - Parameters:
    ///   - text: Input text to synthesize.
    ///   - cfgValue: CFG scale (0.5–5.0).
    ///   - timesteps: Inference steps (1–20).
    ///   - refWavPath: Optional reference audio for voice cloning (pass nil for voice-design mode).
    ///   - outputPath: Destination WAV file.
    /// - Returns: `true` on success.
    public func generate(text: String,
                         cfgValue: Float,
                         timesteps: Int,
                         refWavPath: String?,
                         outputPath: String) async -> Bool {

        guard isInitialized else {
            state = .error(NSError(domain: "TtsEngine", code: -3,
                                   userInfo: [NSLocalizedDescriptionKey: "Engine not initialized. Call loadModel() first."]))
            return false
        }

        state = .generating

        return await withCheckedContinuation { continuation in
            serialQueue.async { [weak self] in
                guard let self = self else {
                    continuation.resume(returning: false)
                    return
                }

                let refPtr = (refWavPath?.isEmpty == false) ? (refWavPath! as NSString).utf8String : nil
                let textPtr = (text as NSString).utf8String
                let outPtr  = (outputPath as NSString).utf8String

                let success = tts_generate(textPtr, cfgValue, Int32(timesteps), refPtr, outPtr)

                Task { @MainActor in
                    self.state = .ready
                }
                continuation.resume(returning: success)
            }
        }
    }

    /// Cancel an ongoing generation.
    public func cancelGeneration() {
        generateTask?.cancel()
        generateTask = nil
        state = .ready
    }

    /// Free all native resources.
    public func destroy() {
        generateTask?.cancel()
        generateTask = nil
        serialQueue.async {
            tts_free()
        }
        isInitialized = false
        state = .uninitialized
    }
}
