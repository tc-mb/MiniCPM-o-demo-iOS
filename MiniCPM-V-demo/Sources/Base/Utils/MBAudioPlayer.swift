//
//  MBAudioPlayer.swift
//  MiniCPM-V-demo
//
//  Wrapper around AVAudioPlayer for WAV playback with progress callbacks.
//  Reads sample rate from WAV header (supports both 16kHz and 48kHz).
//

import Foundation
import AVFoundation

class MBAudioPlayer: NSObject {

    private var player: AVAudioPlayer?
    private var progressTimer: Timer?

    /// Callback for playback progress (0.0–100.0).
    var onProgress: ((Float) -> Void)?
    /// Callback for playback completion.
    var onComplete: (() -> Void)?

    /// Whether currently playing.
    var isPlaying: Bool {
        return player?.isPlaying ?? false
    }

    /// Current playback progress in percentage (0.0–100.0).
    var currentProgress: Float {
        guard let player = player, player.duration > 0 else { return 0 }
        return Float(player.currentTime / player.duration * 100.0)
    }

    /// Total duration in seconds.
    var duration: TimeInterval {
        return player?.duration ?? 0
    }

    /// Current position in seconds.
    var currentTime: TimeInterval {
        return player?.currentTime ?? 0
    }

    // MARK: - Playback control

    /// Play a WAV file.
    func play(url: URL) {
        stop()

        // Ensure audio session is configured for playback
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)

        do {
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            p.prepareToPlay()
            p.play()
            self.player = p

            // Start progress timer (updates ~10 times/sec)
            startProgressTimer()
        } catch {
            print("MBAudioPlayer: Failed to play \(url.lastPathComponent): \(error)")
            onComplete?()
        }
    }

    /// Pause playback (resumable).
    func pause() {
        player?.pause()
        stopProgressTimer()
    }

    /// Resume paused playback.
    func resume() {
        player?.play()
        startProgressTimer()
    }

    /// Stop playback and reset position.
    func stop() {
        player?.stop()
        player = nil
        stopProgressTimer()
    }

    /// Seek to a specific position (0.0–1.0 fraction of total duration).
    func seek(to position: Float) {
        guard let player = player, player.duration > 0 else { return }
        let target = TimeInterval(position) * player.duration
        player.currentTime = max(0, min(target, player.duration))
    }

    // MARK: - Timer

    private func startProgressTimer() {
        stopProgressTimer()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self = self, let player = self.player, player.duration > 0 else { return }
            let progress = Float(player.currentTime / player.duration * 100.0)
            self.onProgress?(progress)
        }
    }

    private func stopProgressTimer() {
        progressTimer?.invalidate()
        progressTimer = nil
    }
}

// MARK: - AVAudioPlayerDelegate

extension MBAudioPlayer: AVAudioPlayerDelegate {
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        stopProgressTimer()
        onProgress?(flag ? 100.0 : currentProgress)
        onComplete?()
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        print("MBAudioPlayer: Decode error: \(error?.localizedDescription ?? "unknown")")
        stop()
        onComplete?()
    }
}
