#!/usr/bin/env python3
"""Unit tests for stock_frontend_inventory helpers (no device required)."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import stock_frontend_inventory as inv


class ParseHelpersTest(unittest.TestCase):
    def test_parse_package_path(self):
        out = "package:/data/app/com.phicomm.speaker.device-1/base.apk\n"
        self.assertEqual(
            inv.parse_package_path(out),
            "/data/app/com.phicomm.speaker.device-1/base.apk",
        )
        self.assertIsNone(inv.parse_package_path(""))
        self.assertIsNone(inv.parse_package_path("error: no package"))

    def test_summarize_audio_flinger_deadlock(self):
        text = "AudioFlinger may be deadlocked\nInput thread ... Unisound"
        s = inv.summarize_audio_flinger(text)
        self.assertTrue(s["mentions_deadlock"])
        self.assertTrue(s["mentions_unisound_or_device_pkg"])

    def test_ensure_fresh_outdir_refuses_existing(self):
        with tempfile.TemporaryDirectory() as td:
            existing = Path(td) / "already"
            existing.mkdir()
            with self.assertRaises(inv.InventoryError):
                inv.ensure_fresh_outdir(existing)

    def test_parse_dumpsys_code_path_and_candidates(self):
        dumpsys = (
            "  Package [com.phicomm.speaker.device] (abc):\n"
            "    codePath=/system/app/Unisound\n"
            "    versionName=V3.2.5.2-\n"
        )
        self.assertEqual(
            inv.parse_dumpsys_code_path(dumpsys), "/system/app/Unisound"
        )
        self.assertIn(
            "/system/app/Unisound/Unisound.apk",
            inv.resolve_apk_candidates("/system/app/Unisound"),
        )
        self.assertTrue(inv.is_allowed_pull_path("/system/app/Unisound/Unisound.apk"))
        self.assertFalse(inv.is_allowed_pull_path("/sdcard/evil.apk"))


    def test_build_report_contains_gates(self):
        md = inv.build_report_md(
            serial="192.168.1.17:5555",
            props={"ro.build.version.sdk": "22"},
            pkg_state={"package": inv.STOCK_PKG, "hidden_heuristic": "true"},
            audio_summary={"mentions_deadlock": False},
            apk_info=None,
            failures=[],
        )
        self.assertIn("stock frontend inventory", md.lower())
        self.assertIn(inv.STOCK_PKG, md)
        self.assertIn("docs/08-stock-agent-bridge.md", md)
        self.assertIn("docs/11-stock-frontend.md", md)


if __name__ == "__main__":
    unittest.main()
