import unittest


class TrainingProtocolTest(unittest.TestCase):
    def test_frozen_test_split_requires_explicit_opt_in(self) -> None:
        from tools.rag_guard.training_protocol import evaluation_split_names

        self.assertEqual(("calibration",), evaluation_split_names(evaluate_test=False))
        self.assertEqual(
            ("calibration", "test"),
            evaluation_split_names(evaluate_test=True),
        )


if __name__ == "__main__":
    unittest.main()
