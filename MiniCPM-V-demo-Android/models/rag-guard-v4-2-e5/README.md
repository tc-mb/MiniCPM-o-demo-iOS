# RAG Guard v4.2 E5 INT8

This directory describes the production RAG Guard model used by the Android demo. It is a
small classifier used around retrieval; it is not the MiniCPM conversational model.

The upstream contribution branch intentionally omits `model.int8.onnx`: GitHub does not allow a
contributor to upload new Git LFS objects to a public fork because the storage belongs to the
upstream repository. The complete public formal-version repository retains the verified object at
[`Si1as-code/MiniCPM-V-Android-Modified`](https://github.com/Si1as-code/MiniCPM-V-Android-Modified/tree/main/MiniCPM-V-demo-Android/models/rag-guard-v4-2-e5).
Before building this contribution branch, download that exact artifact into this directory or set
`RAG_GUARD_ARTIFACT_DIR` to a directory containing the three metadata files and the verified model.

## Artifact identity

- File: `model.int8.onnx`
- Size: `118,171,779` bytes
- SHA-256: `d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2`
- Storage: Git LFS
- Architecture: one multilingual encoder with Answerability and Groundedness heads
- Input limit: 256 tokens using the protected XLM-R pair format documented in
  `../../tools/rag_guard/V4_LABEL_CONTRACT.md`

Answerability labels are `SUPPORTED`, `PARTIAL`, and `UNSUPPORTED`. Groundedness labels are
`GROUNDED`, `PARTIAL`, `UNSUPPORTED`, and `CONTRADICTED`. The shared output has four logits;
the Answerability row pads the fourth logit with `-10000`.

## Provenance and license

The encoder was fine-tuned from
[`intfloat/multilingual-e5-small`](https://huggingface.co/intfloat/multilingual-e5-small),
whose official model page declares the MIT license. Dataset provenance, individual source
licenses, user-accepted ContractNLI terms, transformations and aggregate hashes are recorded in
`../../tools/rag_guard/DATASET_CARD_V4.md`, `../../tools/rag_guard/TRAINING_PREFLIGHT_V4.md`, and
`../../tools/rag_guard/data/dataset_registry_v4.json`. Raw source datasets and private training
directories are not included in this repository.

## Recorded calibration results

- PyTorch/FP32 maximum absolute difference: `0.000008821487426757812`
- INT8/FP32 label agreement: `0.9693585127489162`
- Largest calibration macro-F1 drop: `0.01078691295800005`
- INT8/FP32 size ratio: `0.2512633906971046`
- INT8 Answerability macro-F1: `0.8969041129783651`
- INT8 Groundedness macro-F1: `0.9510476835055687`

These measurements are recorded observations, not a performance release gate. Artifact paths,
byte count, SHA-256, tokenizer identity, ONNX input/output contract, frozen-test isolation and
APK signing remain mandatory integrity checks. The v4.2 frozen test split was not read or
evaluated during this export.

## Checkout and build

For the complete formal-version repository, install Git LFS before cloning, or run the following
after cloning:

```bash
git lfs install
git lfs pull
```

The Android Gradle build uses this directory by default. Set the Gradle property or environment
variable `RAG_GUARD_ARTIFACT_DIR` only when intentionally building from another verified artifact
directory.
