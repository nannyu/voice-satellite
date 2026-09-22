from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import os
from typing import Literal
from urllib.parse import urlsplit


Mode = Literal["passthrough", "fixed_reply"]


def _positive_int(name: str, raw: str) -> int:
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def _positive_float(name: str, raw: str) -> float:
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


@dataclass(frozen=True)
class Settings:
    """Runtime settings with intentionally safe local defaults."""

    mode: Mode = "passthrough"
    upstream_base_url: str = "http://127.0.0.1:18888"
    fixed_reply_text: str = "这是固定回复测试。"
    allowed_client_cidrs: tuple[str, ...] = ("127.0.0.1/32", "::1/128")
    max_request_body_bytes: int = 1_048_576
    max_response_body_bytes: int = 2_097_152
    upstream_timeout_seconds: float = 8.0
    queue_timeout_seconds: float = 0.25
    max_in_flight: int = 8

    def __post_init__(self) -> None:
        if self.mode not in ("passthrough", "fixed_reply"):
            raise ValueError("mode must be passthrough or fixed_reply")

        target = urlsplit(self.upstream_base_url)
        if target.scheme not in ("http", "https") or not target.hostname:
            raise ValueError("upstream_base_url must be an http(s) origin")
        if target.username or target.password:
            raise ValueError("upstream_base_url must not contain credentials")
        if target.query or target.fragment or target.path not in ("", "/"):
            raise ValueError("upstream_base_url must not contain a path, query, or fragment")

        if not self.fixed_reply_text.strip():
            raise ValueError("fixed_reply_text must not be empty")
        if len(self.fixed_reply_text) > 160:
            raise ValueError("fixed_reply_text must be at most 160 characters")

        if not self.allowed_client_cidrs:
            raise ValueError("allowed_client_cidrs must not be empty")
        for network in self.allowed_client_cidrs:
            ipaddress.ip_network(network, strict=False)

        for name in (
            "max_request_body_bytes",
            "max_response_body_bytes",
            "max_in_flight",
        ):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name} must be positive")
        for name in ("upstream_timeout_seconds", "queue_timeout_seconds"):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name} must be positive")

    @property
    def normalized_upstream_base_url(self) -> str:
        return self.upstream_base_url.rstrip("/")

    @property
    def client_networks(self) -> tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...]:
        return tuple(ipaddress.ip_network(item, strict=False) for item in self.allowed_client_cidrs)

    @classmethod
    def from_env(cls) -> "Settings":
        cidrs = tuple(
            value.strip()
            for value in os.getenv(
                "STOCK_GATEWAY_ALLOWED_CLIENT_CIDRS", "127.0.0.1/32,::1/128"
            ).split(",")
            if value.strip()
        )
        return cls(
            mode=os.getenv("STOCK_GATEWAY_MODE", "passthrough").strip(),  # type: ignore[arg-type]
            upstream_base_url=os.getenv(
                "STOCK_GATEWAY_UPSTREAM_BASE_URL", "http://127.0.0.1:18888"
            ).strip(),
            fixed_reply_text=os.getenv(
                "STOCK_GATEWAY_FIXED_REPLY_TEXT", "这是固定回复测试。"
            ),
            allowed_client_cidrs=cidrs,
            max_request_body_bytes=_positive_int(
                "STOCK_GATEWAY_MAX_REQUEST_BODY_BYTES",
                os.getenv("STOCK_GATEWAY_MAX_REQUEST_BODY_BYTES", "1048576"),
            ),
            max_response_body_bytes=_positive_int(
                "STOCK_GATEWAY_MAX_RESPONSE_BODY_BYTES",
                os.getenv("STOCK_GATEWAY_MAX_RESPONSE_BODY_BYTES", "2097152"),
            ),
            upstream_timeout_seconds=_positive_float(
                "STOCK_GATEWAY_UPSTREAM_TIMEOUT_SECONDS",
                os.getenv("STOCK_GATEWAY_UPSTREAM_TIMEOUT_SECONDS", "8"),
            ),
            queue_timeout_seconds=_positive_float(
                "STOCK_GATEWAY_QUEUE_TIMEOUT_SECONDS",
                os.getenv("STOCK_GATEWAY_QUEUE_TIMEOUT_SECONDS", "0.25"),
            ),
            max_in_flight=_positive_int(
                "STOCK_GATEWAY_MAX_IN_FLIGHT",
                os.getenv("STOCK_GATEWAY_MAX_IN_FLIGHT", "8"),
            ),
        )
