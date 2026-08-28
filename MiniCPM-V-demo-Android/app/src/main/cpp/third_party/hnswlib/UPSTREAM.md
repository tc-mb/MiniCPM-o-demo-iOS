# hnswlib provenance

- Upstream: https://github.com/nmslib/hnswlib
- Release: `v0.9.0`
- Commit: `d9b3608c83d83b46c96e25088cb1d729b29dcfe9`
- Source archive: `https://github.com/nmslib/hnswlib/archive/refs/tags/v0.9.0.tar.gz`
- Source archive SHA-256: `65dfb6639cb7d1acbdaeec1429b978fb657a9bf368ebb8353109167394537823`
- License: Apache-2.0; the unmodified upstream `LICENSE` is retained beside this file.

Only the header-only C++ implementation under `hnswlib/` is vendored. Gradle and
CMake builds must not download or update this dependency implicitly.

## Local ARM64 correctness patch

- `hnswlib/hnswalg.h`: replace the potentially misaligned `labeltype*` store in
  `addPoint()` with the existing byte-oriented `setExternalLabel()` helper.
- Rationale and upstream tracking: https://github.com/nmslib/hnswlib/issues/669
- The patch preserves the label bytes and removes undefined behavior reported by
  UBSan on ARM64; it must be dropped only after the pinned upstream release
  contains an equivalent fix.
- `hnswlib/hnswalg.h`: move the existing self-neighbor validation before
  acquiring `link_list_locks_[selectedNeighbors[idx]]`. The original order
  attempted to lock the already-held current-element mutex before it could
  report the invalid self-link, turning a diagnosable graph error into a
  permanent single-thread deadlock.
