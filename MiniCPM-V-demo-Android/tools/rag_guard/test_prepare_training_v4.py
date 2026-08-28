import hashlib
import tempfile
import unittest
from pathlib import Path

from tools.rag_guard.prepare_training_v4 import audit_training_inputs


class PrepareTrainingV4Test(unittest.TestCase):
    def test_ready_source_requires_exact_file_hash_and_size(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_dir = root / "source-a"
            source_dir.mkdir()
            content = b"licensed fixture\n"
            (source_dir / "train.json").write_bytes(content)
            registry = {
                "sources": [
                    {
                        "id": "source-a",
                        "required_for_v4": True,
                        "license_status": "approved",
                        "acquisition_status": "ready",
                        "official_files": [
                            {
                                "name": "train.json",
                                "bytes": len(content),
                                "sha256": hashlib.sha256(content).hexdigest(),
                            }
                        ],
                    }
                ]
            }

            report = audit_training_inputs(registry, root)

            self.assertTrue(report["ready_for_dataset_build"])
            self.assertEqual([], report["blockers"])

    def test_clickthrough_and_partial_download_are_blockers(self) -> None:
        registry = {
            "sources": [
                {
                    "id": "contract",
                    "required_for_v4": True,
                    "license_status": "approved",
                    "acquisition_status": "user_acceptance_required",
                    "official_files": [],
                }
            ]
        }
        with tempfile.TemporaryDirectory() as temporary:
            report = audit_training_inputs(registry, Path(temporary))
        self.assertFalse(report["ready_for_dataset_build"])
        self.assertIn("contract: acquisition status is user_acceptance_required", report["blockers"])


if __name__ == "__main__":
    unittest.main()
