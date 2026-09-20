"""Regression tests for the offline design-bundle validator."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("validate_design.py")
SPEC = importlib.util.spec_from_file_location("validate_design", SCRIPT)
assert SPEC and SPEC.loader
validate_design = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validate_design)
REPO_ROOT = Path(__file__).resolve().parents[2]


class DesignValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name) / "repo"
        self.root.mkdir()
        shutil.copy2(REPO_ROOT / "README.md", self.root / "README.md")
        shutil.copytree(REPO_ROOT / "docs", self.root / "docs")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_current_bundle_passes(self) -> None:
        checks = validate_design.validate(self.root)
        self.assertEqual(5, len(checks))

    def test_missing_source_reference_fails(self) -> None:
        document = self.root / "docs" / "08-stock-agent-bridge.md"
        text = document.read_text(encoding="utf-8")
        document.write_text(text.replace("[S13]", "", 1), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "reference every source"):
            validate_design.validate(self.root)

    def test_empty_source_url_fails(self) -> None:
        path = self.root / "docs" / "stock-agent-bridge" / "sources.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["sources"][-1]["url"] = ""
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Source URL must be HTTPS"):
            validate_design.validate(self.root)

    def test_reference_metadata_drift_fails(self) -> None:
        path = self.root / "docs" / "stock-agent-bridge" / "sources.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["sources"][0]["title"] = "drifted title"
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "reference list"):
            validate_design.validate(self.root)

    def test_acceptance_table_drift_fails(self) -> None:
        document = self.root / "docs" / "08-stock-agent-bridge.md"
        text = document.read_text(encoding="utf-8")
        document.write_text(
            text.replace("连续 20 轮正确", "连续 19 轮正确", 1),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "acceptance table"):
            validate_design.validate(self.root)

    def test_duplicate_doc_number_fails(self) -> None:
        source = self.root / "docs" / "09-wake-word.md"
        shutil.copy2(source, self.root / "docs" / "09-duplicate.md")
        with self.assertRaisesRegex(ValueError, "unique and contiguous"):
            validate_design.validate(self.root)


if __name__ == "__main__":
    unittest.main()
