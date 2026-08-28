# Persistent Conversations Implementation Plan

> **Archived 2026-08-18:** 本计划已完成，统一状态见 [MiniCPM Android 统一进度与后续实施计划](2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史实现细节。
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every conversation and its historical images in app-private storage across app restarts and device reboots.

**Architecture:** Add a versioned, bounded conversation archive codec and atomic disk store. Restore that archive into `ConversationStore` at startup, serialize stable UI mutations through one writer, and move selected image sources plus previews from disposable cache storage to app-private files. Corrupt archives fail closed to a fresh session without crashing; conversation files are excluded from Android cloud backup.

**Tech Stack:** Kotlin, Android app-private files, coroutines, JUnit 4, Gradle.

---

- [x] Add failing archive round-trip, corruption, bounds, restore-ID, and atomic-store tests.
- [x] Implement immutable archive snapshots, strict versioned codec, and atomic disk replacement.
- [x] Persist image originals/previews under `filesDir` and restore safe thumbnails.
- [x] Load saved sessions on startup and save after every stable conversation mutation.
- [x] Exclude conversation text and images from Android cloud backup/device transfer.
- [x] Run unit tests, assemble the debug APK, and inspect the final diff for privacy and data-loss risks.
