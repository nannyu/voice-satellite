from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Iterable, Mapping


STOCK_ROUTE_PATH = "/trafficRouter/cs"

# Narrow list observed in the pinned reference implementation. Credentials and
# cookies are deliberately absent; Host is generated from the configured origin.
FORWARD_REQUEST_HEADERS = frozenset(
    {
        "accept-encoding",
        "ci",
        "content-type",
        "cryp",
        "dt",
        "http-client-ip",
        "i",
        "k",
        "p",
        "remote-addr",
        "sp",
        "t",
        "tp",
        "u",
        "ui",
        "user-agent",
    }
)

HOP_BY_HOP_HEADERS = frozenset(
    {
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    }
)

DROP_RESPONSE_HEADERS = HOP_BY_HOP_HEADERS | frozenset(
    {"content-length", "set-cookie", "server"}
)


@dataclass(frozen=True)
class RewriteResult:
    body: bytes
    rewritten: bool
    asr_present: bool


def build_forward_headers(headers: Mapping[str, str], *, force_identity: bool) -> dict[str, str]:
    forwarded = {
        name.lower(): value
        for name, value in headers.items()
        if name.lower() in FORWARD_REQUEST_HEADERS
    }
    if force_identity:
        forwarded["accept-encoding"] = "identity"
    return forwarded


def build_response_headers(headers: Iterable[tuple[str, str]]) -> dict[str, str]:
    return {
        name: value
        for name, value in headers
        if name.lower() not in DROP_RESPONSE_HEADERS
    }


def rewrite_fixed_reply(body: bytes, text: str) -> RewriteResult:
    """Replace only a final stock JSON answer; all unknown frames pass through."""

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return RewriteResult(body=body, rewritten=False, asr_present=False)

    if not isinstance(payload, dict):
        return RewriteResult(body=body, rewritten=False, asr_present=False)

    asr_text = payload.get("asr_recongize")
    asr_present = isinstance(asr_text, str) and bool(asr_text.strip())
    general = payload.get("general")
    is_final_answer = bool(payload.get("responseId")) and isinstance(general, dict)
    if not (asr_present and is_final_answer):
        return RewriteResult(body=body, rewritten=False, asr_present=asr_present)

    general["text"] = text
    rewritten = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return RewriteResult(body=rewritten, rewritten=True, asr_present=True)
