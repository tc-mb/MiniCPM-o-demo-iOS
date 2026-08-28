# RAG Guard v4.2 E5 Export and Android APK Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export the selected v4.2 E5 three-plus-four-class checkpoint to verified FP32 ONNX and per-tensor INT8 ONNX, bundle the verified INT8 artifact into the APK, and make Android inference reproduce the v4 training contract.

**Architecture:** Upgrade the exporter and Android runtime from the retired v3 `3+3` contract to v4 `3+4`, while keeping one shared four-logit tensor whose Answerability row pads logit 4 with `-10000`. Export equivalence is evaluated only on the frozen calibration split; frozen test stays unopened. Gradle copies the externally stored verified model into generated APK assets, and the app atomically installs and hash-verifies that bundled asset in private storage before opening ONNX Runtime.

**Tech Stack:** Python 3.12, PyTorch 2.4.1 CPU, Transformers 4.53.3, ONNX 1.19.0, ONNX Runtime 1.23.2, Kotlin/JVM, Android assets, Gradle 9.6.1, AGP 9.3.0, JUnit, Android instrumentation.

---

### Task 1: Freeze the v4 export contract

**Files:**
- Modify: `tools/rag_guard/test_export_onnx.py`
- Modify: `tools/rag_guard/export_onnx.py`

- [x] **Step 1: Write failing tests for the `3+4` manifest and calibration-only boundary**

Assert architecture `shared_encoder_three_plus_four_heads`, output `float32[batch,4]`, four Groundedness labels, three Answerability labels, padding logit `-10000`, and an evaluated split list containing only `calibration`.

- [x] **Step 2: Run the focused Python tests and confirm v3 assertions fail**

Run: `python -m unittest tools.rag_guard.test_export_onnx -v`

Expected: failures because the exporter still emits the v3 `3+3` contract.

- [x] **Step 3: Implement the minimal v4 exporter contract**

Use `LABELS_BY_TASK_V4`, `load_jsonl_v4`, `format_model_pair_v4`, and the exact pair tokenizer path used by training. Load only `answerability_calibration.jsonl` and `groundedness_calibration.jsonl`; do not accept or open any test filename. Compare PyTorch and FP32 ONNX on a bounded calibration subset, then compare FP32 and INT8 on all calibration rows.

- [x] **Step 4: Re-run focused and full dependency-free RAG Guard tests**

Run: `python -m unittest tools.rag_guard.test_export_onnx -v`

Run: `python -m pytest tools/rag_guard -q`

Expected: all dependency-free tests pass; tensor tests run after the export environment is installed.

### Task 2: Upgrade the Android inference contract to four logits

**Files:**
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagGuardInferenceContractTest.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagGuardModelManifestTest.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagOutputReviewPolicyTest.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardInput.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardClassifier.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/OnnxRagGuardClassifier.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardModelManifest.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagOutputReviewPolicy.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagReviewedGenerator.kt`

- [x] **Step 1: Write failing Kotlin tests for four labels and XLM-R pair assembly**

Test Answerability softmax over only the first three logits, Groundedness softmax over four logits, `CONTRADICTED` decoding, and pair IDs in the exact form `<s> protected </s></s> evidence </s>` with truncation restricted to evidence.

- [x] **Step 2: Verify the Kotlin tests fail for the expected v3 assumptions**

Run: `gradlew :app:testDebugUnitTest --tests '*RagGuardInferenceContractTest' --tests '*RagGuardModelManifestTest' --tests '*RagOutputReviewPolicyTest'`

Expected: failures from three-logit validation, missing fourth label, and single-sequence input construction.

- [x] **Step 3: Implement the minimal v4 runtime contract**

Add `UNSUPPORTED` and `CONTRADICTED`; remove retired `UNGROUNDED`. Decode task-specific logit counts. Make `UNSUPPORTED` fall back to normal chat, make `CONTRADICTED` immediately replace the candidate with knowledge-base evidence, and retain one regeneration for `PARTIAL`. Keep raw model input and answers out of logs.

- [x] **Step 4: Run focused and complete JVM tests**

Run: `gradlew :app:testDebugUnitTest`

Expected: all JVM tests pass.

### Task 3: Bundle and atomically install the verified model

**Files:**
- Create: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagGuardBundledModelInstallerTest.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardBundledModelInstaller.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagGuardModelManager.kt`
- Modify: `app/build.gradle.kts`

- [x] **Step 1: Write failing installer tests**

Test first install, valid-file reuse, corrupted-file replacement, interrupted temporary-file cleanup, canonical path containment, exact byte count, and exact SHA-256.

- [x] **Step 2: Verify installer tests fail because the installer is absent**

Run: `gradlew :app:testDebugUnitTest --tests '*RagGuardBundledModelInstallerTest'`

- [x] **Step 3: Implement atomic private-storage installation**

Copy the fixed asset name into a same-directory temporary file, flush and sync it, verify size and SHA-256, and atomically rename it. Never derive a path from user input. Preserve an already valid installed model and delete only the bounded temporary file on failure.

- [x] **Step 4: Add a generated-assets Gradle pipeline**

Read the verified external artifact directory from `RAG_GUARD_ARTIFACT_DIR`, defaulting to `D:\MiniCPM-V\artifacts\rag-guard-v4-2-e5`. Validate `manifest.json`, model bytes, and SHA-256 before `mergeDebugAssets` or `mergeReleaseAssets`, copy `model.int8.onnx` under `rag_guard_v4_2/`, and package `.onnx` uncompressed. Do not commit the binary to Git.

- [x] **Step 5: Run installer and model-manager tests**

Run: `gradlew :app:testDebugUnitTest --tests '*RagGuardBundledModelInstallerTest' --tests '*RagGuardModelManagerTest'`

Expected: all pass.

### Task 4: Export and quantify the selected E5 checkpoint

**Files:**
- Input: `D:\MiniCPM-V\private-training\rag-guard-v4-2\evidence\e5-calibration-e5`
- Input: `D:\MiniCPM-V\private-training\rag-guard-v4\model-base\multilingual-e5-small`
- Input: `D:\MiniCPM-V\private-training\rag-guard-v4-2\generated\splits-e`
- Generated: `D:\MiniCPM-V\artifacts\rag-guard-v4-2-e5`

- [x] **Step 1: Create an isolated local CPU export environment**

Create `D:\MiniCPM-V\.venv-rag-export` with the bundled Python, install exact pinned CPU PyTorch, training dependencies, and export dependencies, and run `pip check`.

- [x] **Step 2: Export FP32 ONNX and per-tensor INT8**

Run `tools.rag_guard.export_onnx` with max length 256 and the pinned Android tokenizer SHA-256 `3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe`.

- [x] **Step 3: Record equivalence observations without a performance gate**

Record FP32/PyTorch maximum absolute delta, INT8/FP32 label agreement, calibration macro-F1 change, and INT8/FP32 size ratio exactly as measured. Per the final product decision, these measurements do not block APK integration. Integrity, frozen-test isolation, model identity and runtime-contract mismatches still fail closed.

- [x] **Step 4: Record immutable artifact evidence**

Record model bytes, SHA-256, quantization metrics, versions, evaluated split `calibration`, and `test_evaluated=false`. Delete neither the checkpoint nor failed outputs.

### Task 5: Build and verify the APK

**Files:**
- Modify: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/guard/RagGuardInstrumentedTest.kt`
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Modify: `README_MODIFIED_zh.md`

- [x] **Step 1: Add an APK asset/inference instrumentation assertion**

Assert the bundled asset installs to the v4.2 private directory, exact model identity is used, Answerability returns three-class semantics, Groundedness returns four-class semantics, and repeated CPU inference is stable.

- [x] **Step 2: Build the signed debug APK with the canonical key**

Run: `gradlew verifyInstallationSigning :app:assembleDebug`

Expected: signing fingerprint passes, generated model asset is present, and APK builds successfully.

- [x] **Step 3: Verify APK contents and signatures**

Use ZIP inspection or Android build tools to confirm the model asset is stored uncompressed in the APK, verify its exact size and SHA-256 against the externally validated manifest, and run `apksigner verify --print-certs` against the canonical certificate.

- [x] **Step 4: Run device checks when a device is connected**

Run the focused `RagGuardInstrumentedTest` and installation-persistence test. If no device is connected, record this as the only deferred acceptance item; do not block export, quantization, JVM tests, or APK construction.

Completed on vivo V2359A. The persistence test was corrected to distinguish immutable user data from the intentional v3-to-v4.2 Guard artifact migration: conversations, messages, knowledge bases, documents, E5 identity and HNSW aggregates remained identical, while Guard had to equal the pinned v4.2 SHA-256. `RagGuardInstrumentedTest` passed 30 stable runs: model open `1441.170 ms`, Answerability P50/P95 `8.245/8.475 ms`, Groundedness P50/P95 `10.505/11.755 ms`.

### Task 6: Update durable project records

**Files:**
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Modify: `tools/rag_guard/DATASET_CARD_V4.md`
- Modify: `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`
- Modify: `graphify-out/*`

- [x] **Step 1: Record export and APK evidence**

Document the selected E5 checkpoint hash, FP32/INT8 hashes and sizes, alignment metrics, package path, signing result, runtime contract, and any deferred device-only test.

- [x] **Step 2: Run full verification**

Run Python tests, JVM tests, debug APK assembly, APK content validation, and device tests when available.

- [x] **Step 3: Update Graphify incrementally**

Run the installed Graphify update, retain parser warnings, and query the E5 export-to-APK path to confirm it is represented.

Completed with the existing warnings: five JSON evidence files produced zero AST nodes and seven C/C++ files were partially parsed. `check-update` exited successfully, and a focused query resolved `CurrentRagGuardModel`, `RagGuardBundledModelInstaller`, its tests and the private-install call chain.

---

## Self-review

- Spec coverage: E5 selection, v4 `3+4` contract, calibration-only quantization, secure APK bundling, runtime installation, signing, tests, documentation and Graphify are all assigned.
- Frozen-test boundary: no task reads or evaluates v4.2 test files.
- Binary handling: the ONNX model is generated outside Git and copied into generated APK assets only.
- Failure behavior: path, hash, size, frozen-test boundary, signing and runtime-contract mismatches fail closed before installation. Quantization performance differences are recorded without blocking integration.
- Execution mode: the user requested immediate implementation, so this plan is executed inline without sub-agent delegation.
