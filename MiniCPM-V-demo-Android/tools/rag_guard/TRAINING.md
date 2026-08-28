# RAG guard training

The current v4 training tool fine-tunes one multilingual encoder with a three-class
Answerability head and a four-class Groundedness head:

- Answerability: `SUPPORTED / PARTIAL / UNSUPPORTED`
- Groundedness: `GROUNDED / PARTIAL / UNSUPPORTED / CONTRADICTED`

The model input is `query + evidence` for Answerability and `query + evidence + answer` for
Groundedness. Raw text is never written to the training log.

Install the pinned dependencies into the selected Conda environment, then run:

```bash
python -m pip install -r tools/rag_guard/requirements-train.txt
python -m tools.rag_guard.train \
  --model intfloat/multilingual-e5-small \
  --data-dir tools/rag_guard/data/generated \
  --output-dir runs/rag-guard-dual-head \
  --epochs 4 \
  --batch-size 16 \
  --eval-batch-size 32 \
  --gradient-accumulation 2 \
  --max-length 256 \
  --learning-rate 2e-5 \
  --bf16
```

The primary selection score is the mean macro-F1 of both heads:

$$
S = \frac{F1_{answerability} + F1_{groundedness}}{2}
$$

When two checkpoints have the same score, the checkpoint with the lower mean expected
calibration error is retained. The output directory contains the Safetensors checkpoint,
tokenizer, encoder configuration, manifest, aggregate metrics, and no source documents.

The current generated corpus is suitable for validating the pipeline and label contract. A
perfect result on this structurally regular synthetic corpus is not evidence of production
quality; anonymized real-distribution regression data is still required before runtime enablement.

## Export the Android model package

Install the separate, pinned export dependencies into the same selected Conda environment:

```bash
python -m pip install -r tools/rag_guard/requirements-export.txt
python -m tools.rag_guard.export_onnx \
  --checkpoint-dir runs/rag-guard-dual-head \
  --base-model /path/to/multilingual-e5-small \
  --data-dir tools/rag_guard/data/generated \
  --regression-path tools/rag_guard/data/regression_seeds.jsonl \
  --output-dir runs/rag-guard-dual-head-onnx \
  --tokenizer-sha256 3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe
```

The exporter produces one shared-encoder, dual-head ONNX model. `task_ids=0` selects
Answerability and `task_ids=1` selects Groundedness. The shared output has four logits;
Answerability uses the first three and pads the fourth with `-10000`. The exporter dynamically
quantizes `MatMul`, `Gemm`, and `Gather` weights to per-tensor INT8, validates the ONNX I/O
contract, compares PyTorch, FP32 ONNX, and INT8 ONNX predictions, and evaluates only the
calibration split. It must not open the frozen v4.2 test split.

Performance comparisons are recorded in `quantization_metrics.json` and `manifest.json`; they
do not block production export. Artifact integrity remains mandatory: controlled paths, exact
model byte count, SHA-256, tokenizer identity, ONNX input/output contract, and APK signing must
all verify successfully.

The 2026-08-28 production INT8 export is 118,171,779 bytes with SHA-256
`d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2`. Its compression ratio
is 0.2512633907, calibration label agreement is 0.9693585127, and the largest calibration
macro-F1 drop is 0.0107869130. These numbers are recorded observations, not a release gate.
The manifest states `test_evaluated=false` and `test=null`.
