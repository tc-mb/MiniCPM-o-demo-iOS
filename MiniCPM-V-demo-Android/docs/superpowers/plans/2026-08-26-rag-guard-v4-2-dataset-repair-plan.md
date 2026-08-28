# RAG Guard v4.2 Dataset Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the RAG Guard corpus as an independently versioned v4.2 dataset whose QA evidence remains visible at 256 tokens, whose relation-binding distractors are type-compatible, whose temporal mutations are labeled correctly, and whose Chinese Answerability examples use natural cross-document questions.

**Architecture:** Preserve schema v2 and the external Answerability 3-class / Groundedness 4-class contracts. Add deterministic QA repair helpers, pass the pinned local tokenizer into full corpus generation, reject families whose decisive evidence is truncated, and select Answerability with frozen label-by-language quotas. Write only to `D:\MiniCPM-V\private-training\rag-guard-v4-2`; v4.1 inputs, splits, audits, checkpoints, and hashes remain immutable controls.

**Tech Stack:** Python 3.10+, Hugging Face fast tokenizer, JSONL, SHA-256, `unittest`/`pytest`, existing RAG Guard schema/audit/split tools.

---

### Task 1: Freeze v4.2 contracts and repair helpers

**Files:**
- Create: `tools/rag_guard/qa_repairs_v4_2.py`
- Create: `tools/rag_guard/test_qa_repairs_v4_2.py`
- Modify: `tools/rag_guard/build_full_corpus_v4.py`

- [x] **Step 1: Write failing tests for temporal classification and answer type matching**

```python
self.assertEqual("WRONG_DATE", classify_numeric_hard_type("15 July 2007", "en"))
self.assertEqual("WRONG_DATE", classify_numeric_hard_type("2013", "en"))
self.assertEqual("WRONG_DATE", classify_numeric_hard_type("2012年3月", "zh"))
self.assertEqual("WRONG_AMOUNT", classify_numeric_hard_type("24", "en"))
self.assertEqual("Paris", choose_type_matched_distractor("London", ["24", "Paris"]))
```

- [x] **Step 2: Run the focused tests and confirm missing APIs fail**

Run: `python -m unittest tools.rag_guard.test_qa_repairs_v4_2 -v`

Expected: import failures for the new helper functions.

- [x] **Step 3: Implement bounded date/type helpers**

Implement pure functions with compiled, bounded regular expressions; reject blank/oversized values; never evaluate input or construct shell commands.

- [x] **Step 4: Verify focused and full dependency-free tests**

Run: `python -m unittest tools.rag_guard.test_qa_repairs_v4_2 -v`

Expected: all focused tests pass.

### Task 2: Build tokenizer-bounded evidence windows

**Files:**
- Modify: `tools/rag_guard/qa_repairs_v4_2.py`
- Modify: `tools/rag_guard/test_qa_repairs_v4_2.py`
- Modify: `tools/rag_guard/build_full_corpus_v4.py`

- [x] **Step 1: Write a failing fake-tokenizer test**

```python
window = build_visible_evidence_window(
    context="prefix " * 500 + "the answer" + " suffix" * 500,
    required_texts=("the answer",),
    protected_text="query: What is it?\nanswer: the answer",
    tokenizer=FakeOffsetTokenizer(),
    max_length=64,
)
self.assertIn("the answer", window)
self.assertLessEqual(pair_token_count(protected_text, window), 64)
```

- [x] **Step 2: Confirm the missing window helper fails**

Run the exact focused test with `unittest -v` and verify failure is caused by the absent helper.

- [x] **Step 3: Implement an offset-based token window**

Tokenize protected text to calculate the second-sequence budget, tokenize evidence without special tokens and with offsets, require every decisive span to fit, expand symmetrically within the remaining token budget, and perform a final pair-token verification. Return `None` when required spans cannot coexist within 256 tokens.

- [x] **Step 4: Pass the pinned tokenizer into QA generation**

Load the local tokenizer before `build_all_sources`; full builds require it. For answerable QA rows, center evidence on the true answer plus a selected type-compatible distractor. For native impossible rows, use a `plausible_answers` span when present. Smoke builds without a tokenizer retain bounded legacy behavior but cannot produce a release manifest.

### Task 3: Replace template Chinese negatives with natural cross-document questions

**Files:**
- Modify: `tools/rag_guard/build_full_corpus_v4.py`
- Modify: `tools/rag_guard/test_build_full_corpus_v4.py`

- [x] **Step 1: Write a failing CMRC test with two documents**

Assert that CMRC `UNSUPPORTED` and `PARTIAL` rows borrow a natural question from another document, contain no generated reference code, and retain the current document's evidence.

- [x] **Step 2: Implement deterministic cross-document selection**

Materialize QA paragraphs, build a source-local pool of natural answerable questions, select the first seeded candidate from a different `document_id` whose answer is absent from the target evidence, and emit no artificial reference code in a full build. If no safe candidate exists, skip the derived negative family instead of fabricating a template.

- [x] **Step 3: Run QA corpus tests**

Run: `python -m unittest tools.rag_guard.test_build_full_corpus_v4 -v`

Expected: natural-negative, type-match, date-label, and existing four-class family tests pass.

### Task 4: Add language quotas and evidence-visibility release gates

**Files:**
- Modify: `tools/rag_guard/build_full_corpus_v4.py`
- Modify: `tools/rag_guard/dataset_correctness_v4.py`
- Modify: `tools/rag_guard/test_dataset_correctness_v4.py`
- Modify: `tools/rag_guard/test_build_full_corpus_v4.py`

- [x] **Step 1: Write failing quota and visibility tests**

Assert that Answerability selects frozen per-label/per-language counts, English `WRONG_DATE` receives a material quota, total contradiction rows remain 37,500, and a family with truncated decisive evidence is rejected.

- [x] **Step 2: Implement supply-bounded v4.2 quotas**

Use the actual approved-source supply as a hard upper bound: retain 600 Chinese rows per Answerability label and 600/450/40/70/10 Chinese contradiction rows by hard type, then redistribute each unavailable Chinese cell to the same-label or same-hard-type English cell without changing the 37,500 Groundedness contradiction total; fail closed when any cell lacks candidates.

- [x] **Step 3: Implement decisive-evidence visibility audit**

For QA `SUPPORTED`/`PARTIAL` and Groundedness `GROUNDED`/`CONTRADICTED` rows, resolve the family GROUNDED answer, inspect tokenizer offsets for sequence 2, and reject the entire family if the decisive answer span is absent after 256-token encoding.

- [x] **Step 4: Run all RAG Guard tests**

Run: `python -m unittest discover -s tools/rag_guard -p 'test_*.py'`

Expected: all dependency-free tests pass; PyTorch-only tests may skip locally and must pass on the training host before retraining.

### Task 5: Generate and audit an isolated v4.2 corpus

**Files:**
- Generated outside Git: `D:\MiniCPM-V\private-training\rag-guard-v4-2\generated`
- Modify: `tools/rag_guard/DATASET_CARD_V4.md`
- Modify: `tools/rag_guard/TRAINING_RUN_V4.md`
- Modify: `tools/rag_guard/SMOKE_ERROR_AUDIT_V4_1.md`

- [x] **Step 1: Run a bounded smoke build**

Use the pinned local multilingual E5 tokenizer, source registry, raw corpus and a new transform/seed. Verify natural Chinese negatives, typed relation distractors, date distribution and decisive evidence visibility before a full build.

- [x] **Step 2: Run the full v4.2 build into a new root**

Never delete or overwrite v4.1. Write JSONL and manifests atomically, then validate schema, privacy/license status, exact IDs, family integrity, near-duplicate leakage, label/language quotas and tokenizer visibility.

- [x] **Step 3: Split by document/conversation/mutation/translation/near-duplicate families**

Generate train/calibration/test files with a new split seed. Verify pairwise intersection size is zero for every protected family key and freeze every output SHA-256.

- [x] **Step 4: Compare v4.1 and v4.2 distributions**

Report removed reference templates, relation distractor type compatibility, declared/content-derived temporal counts, Chinese Answerability share, decisive evidence truncation count, and candidate rejection reasons.

### Task 6: Update graph and stop before retraining

**Files:**
- Modify: `graphify-out/*`
- Modify: `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`

- [x] **Step 1: Update Graphify incrementally**

Run the installed Graphify update, retain its health warnings, and verify the new helper and audit relationships are queryable.

- [x] **Step 2: Record the next controlled experiment**

The data-repair phase stopped after v4.2 corpus and split audits. V4.2 E1 diagnostics and the matched five-epoch E5 versus fixed-revision NLI-initialized calibration-only comparison have now completed on the authorized RTX 3080 host with `--evaluate-test` omitted. Calibration selects E5; frozen test remains unopened. Graphify update retains known parser warnings rather than hiding them.

### Task 7: Complete and archive the calibration-only architecture A/B

- [x] Train E5 and fixed-commit NLI initialization for five epochs with identical data and hyperparameters.
- [x] Verify both metrics and manifests record `test_evaluated=false` and `test=null` where applicable.
- [x] Run calibration-only checkpoint audits and report five-epoch histories plus eight hard slices.
- [x] Re-slice relation binding and date-like amount content by source and language; compare against independent E1 checkpoints.
- [x] Run the remote full test suite (`139 passed, 9 subtests passed`).
- [x] Record model, aggregate audit and error-list SHA-256 values; back up both runs and verify 16 local hashes.
- [x] Select E5 from calibration only; do not export Android or evaluate frozen test.
- [ ] Add in-process peak VRAM telemetry to the next training launcher; this run did not record a trustworthy peak and the acceptance manifest intentionally stores `null`.

---

## Self-review

- Spec coverage: evidence truncation, relation binding, temporal labeling, Chinese natural negatives, language quotas, isolated output, audits, hashes and Graphify are each assigned to a task.
- Placeholder scan: no `TBD`, deferred implementation placeholder or unspecified test remains.
- Type consistency: helper names and file paths are identical across tests, implementation and build integration.
- Execution mode: the user explicitly requested implementation, so this plan is executed inline without sub-agent delegation.
