"""Validate the design bundle only; no network or device operations."""

from __future__ import annotations

from datetime import date
import json
from pathlib import Path
import re
import sys
from typing import Any, Dict, List


EXPECTED_SOURCE_IDS = [f"S{number}" for number in range(1, 14)]
EXPECTED_ACCEPTANCE_IDS = [f"A{number:02d}" for number in range(1, 21)]


def _read_json(path: Path) -> Dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected JSON object: {path.name}")
    return value


def _normalize_cell(value: str) -> str:
    return " ".join(value.strip().split())


def _validate_numbered_docs(repo_root: Path, readme: str) -> None:
    docs_dir = repo_root / "docs"
    numbered = sorted(docs_dir.glob("[0-9][0-9]-*.md"))
    numbers = [int(path.name[:2]) for path in numbered]
    expected = list(range(1, max(numbers, default=0) + 1))
    if numbers != expected:
        raise ValueError(
            f"Numbered docs must be unique and contiguous: got {numbers}, expected {expected}"
        )
    for number, path in zip(numbers, numbered):
        first_line = path.read_text(encoding="utf-8").splitlines()[0]
        if not first_line.startswith(f"# {number:02d}."):
            raise ValueError(f"Heading number does not match filename: {path.name}")
        target = f"docs/{path.name}"
        if f"]({target})" not in readme:
            raise ValueError(f"Root README does not link numbered doc: {target}")


def _validate_markdown_and_examples(document: str, examples: Dict[str, Any]) -> None:
    fences = re.findall(r"^```.*$", document, flags=re.M)
    if len(fences) % 2:
        raise ValueError("Unbalanced fenced code blocks")
    blocks = [
        json.loads(raw)
        for raw in re.findall(r"```json\n(.*?)\n```", document, flags=re.S)
    ]
    if len(blocks) != 2:
        raise ValueError("Expected two JSON examples in 08-stock-agent-bridge.md")

    if examples.get("document_status") != "design_only_not_implemented":
        raise ValueError("Missing implementation-status boundary")
    if examples.get("version") != "0.1":
        raise ValueError("Unexpected API example version")
    if not _normalize_cell(str(examples.get("scope", ""))):
        raise ValueError("API example scope must be non-empty")

    request = examples["create_turn"]["body"]
    result = examples["completed_response"]["body"]
    if blocks != [request, result]:
        raise ValueError("Document and separate API examples do not match")
    for field in ("request_id", "turn_id", "generation"):
        if request[field] != result[field]:
            raise ValueError(f"Mismatched request/result field: {field}")
    turn_id = request["turn_id"]
    if examples["read_turn"]["path"] != f"/voice/v1/turns/{turn_id}":
        raise ValueError("Read path does not match the example turn_id")
    if examples["cancel_turn"]["path"] != f"/voice/v1/turns/{turn_id}/cancel":
        raise ValueError("Cancel path does not match the example turn_id")
    if examples["cancel_turn"]["body"]["target_generation"] != request["generation"]:
        raise ValueError("Cancellation target generation mismatch")
    cancel_result = examples["cancel_response_example"]["body"]
    if cancel_result.get("execution_stopped") is not None:
        raise ValueError("Example should not assume cancellation success")
    if cancel_result.get("side_effects_reverted") is not False:
        raise ValueError("Example should not claim side effects were reverted")
    capabilities = examples["capability_defaults_before_validation"]
    if not capabilities or any(value is not None for value in capabilities.values()):
        raise ValueError("Unvalidated capabilities must all default to null")


def _validate_acceptance(document: str, matrix: Dict[str, Any]) -> None:
    if matrix.get("status") != "proposed_targets_not_benchmark_results":
        raise ValueError("Acceptance matrix status must remain proposed-only")
    if matrix.get("version") != "0.1":
        raise ValueError("Unexpected acceptance matrix version")
    cases = matrix["cases"]
    ids = [case["id"] for case in cases]
    if ids != EXPECTED_ACCEPTANCE_IDS:
        raise ValueError("Acceptance IDs must be unique and cover A01 through A20")
    for case in cases:
        if case.get("status") != "not_run":
            raise ValueError("Design bundle must not claim device tests have run")
        if not _normalize_cell(str(case.get("scenario", ""))):
            raise ValueError(f"Acceptance scenario is empty: {case['id']}")
        if not _normalize_cell(str(case.get("proposed_target", ""))):
            raise ValueError(f"Acceptance target is empty: {case['id']}")

    table_rows = re.findall(
        r"^\| (A\d{2}) \| (.*?) \| (.*?) \|$", document, flags=re.M
    )
    expected_rows = [
        (
            case["id"],
            _normalize_cell(case["scenario"]),
            _normalize_cell(case["proposed_target"]),
        )
        for case in cases
    ]
    actual_rows = [
        (case_id, _normalize_cell(scenario), _normalize_cell(target))
        for case_id, scenario, target in table_rows
    ]
    if actual_rows != expected_rows:
        raise ValueError("Markdown acceptance table and acceptance-matrix.json differ")


def _validate_sources(document: str, sources: Dict[str, Any]) -> None:
    entries = sources["sources"]
    ids = [entry.get("id") for entry in entries]
    if ids != EXPECTED_SOURCE_IDS:
        raise ValueError("Source IDs must be unique and ordered S1 through S13")
    for entry in entries:
        source_id = entry["id"]
        title = _normalize_cell(str(entry.get("title", "")))
        url = str(entry.get("url", "")).strip()
        verified_on = str(entry.get("verified_on", ""))
        if not title:
            raise ValueError(f"Source title is empty: {source_id}")
        if not url.startswith("https://"):
            raise ValueError(f"Source URL must be HTTPS: {source_id}")
        try:
            date.fromisoformat(verified_on)
        except ValueError as exc:
            raise ValueError(f"Source verification date is invalid: {source_id}") from exc

    body, separator, _reference_section = document.partition("## 参考来源")
    if not separator:
        raise ValueError("Missing Markdown reference section")
    used = sorted(
        set(re.findall(r"\[(S\d+)\]", body)),
        key=lambda value: int(value[1:]),
    )
    if used != EXPECTED_SOURCE_IDS:
        raise ValueError(f"Document body must reference every source by ID; got {used}")

    reference_rows = re.findall(
        r"^\[(S\d+)\] \[(.+?)\]\((https://[^)]+)\)$", document, flags=re.M
    )
    expected_rows = [
        (entry["id"], entry["title"], entry["url"])
        for entry in entries
    ]
    if reference_rows != expected_rows:
        raise ValueError("Markdown reference list and sources.json differ")

    repository_refs = sources.get("repository_refs")
    if not isinstance(repository_refs, dict) or not repository_refs:
        raise ValueError("repository_refs must be a non-empty object")
    all_urls = "\n".join(entry["url"] for entry in entries)
    for repository, revision in repository_refs.items():
        if not re.fullmatch(r"[0-9a-f]{40}", str(revision)):
            raise ValueError(f"Repository revision must be a full commit: {repository}")
        if f"github.com/{repository}" not in all_urls or str(revision) not in all_urls:
            raise ValueError(f"Pinned repository revision is not represented in sources: {repository}")


def validate(repo_root: Path | None = None) -> List[str]:
    repo_root = (repo_root or Path(__file__).resolve().parents[2]).resolve()
    root = repo_root / "docs" / "stock-agent-bridge"
    document = (repo_root / "docs" / "08-stock-agent-bridge.md").read_text(
        encoding="utf-8"
    )
    readme = (repo_root / "README.md").read_text(encoding="utf-8")
    examples = _read_json(root / "agent-api-examples.json")
    matrix = _read_json(root / "acceptance-matrix.json")
    sources = _read_json(root / "sources.json")

    _validate_numbered_docs(repo_root, readme)
    _validate_markdown_and_examples(document, examples)
    _validate_acceptance(document, matrix)
    _validate_sources(document, sources)
    return [
        "Numbered docs are unique, contiguous, heading-matched, and linked",
        "Markdown fences and embedded API JSON are valid",
        "Request/result/read/cancel IDs, generation, and capability defaults agree",
        "20 proposed acceptance cases match Markdown and remain not_run",
        "S1-S13 metadata, HTTPS URLs, pinned revisions, and Markdown references agree",
    ]


def main() -> int:
    try:
        for entry in validate():
            print("PASS:", entry)
        print("Scope: document consistency only; no device/network/service tests were run.")
        return 0
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
