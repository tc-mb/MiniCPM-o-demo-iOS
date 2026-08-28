# RAG Guard v4 label contract

Guard v4 keeps one shared multilingual encoder with two different output contracts:

- Answerability: `SUPPORTED / PARTIAL / UNSUPPORTED`.
- Groundedness: `GROUNDED / PARTIAL / UNSUPPORTED / CONTRADICTED`.

`UNSUPPORTED` means the evidence does not establish the candidate answer and does not
explicitly refute its material claims. `CONTRADICTED` means at least one material claim is
explicitly refuted by the evidence. A contradicted amount, date, entity, unit, polarity,
scope, version, or citation makes the whole Groundedness row `CONTRADICTED`.

The v3 Groundedness label `UNGROUNDED` is ambiguous between missing support and explicit
contradiction. It must not be silently renamed or converted. V3 checkpoints, manifests, and
datasets remain versioned separately. Any v3 row used in v4 must be re-labelled from the
question, evidence, answer, and atomic-claim annotations.

Manifest schema version 2 records three Answerability labels, four Groundedness labels, and
a four-logit ONNX output. The fourth Answerability logit is a fixed masked value and is never
included in the Answerability softmax.
