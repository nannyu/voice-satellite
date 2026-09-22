from __future__ import annotations

import asyncio
import json
import logging

import httpx
import pytest

from stock_gateway.app import create_app
from stock_gateway.config import Settings


FINAL_PAYLOAD = {
    "code": "ANSWER",
    "asr_recongize": "今天天气怎么样",
    "responseId": "response-001",
    "audioUrl": "http://asrv3.hivoice.cn/trafficRouter/r/TRdECS",
    "general": {"text": "原厂答案", "type": "T", "resourceId": "904757"},
}


def settings(**overrides) -> Settings:
    values = {
        "mode": "passthrough",
        "upstream_base_url": "http://stock-upstream.invalid",
        "allowed_client_cidrs": ("127.0.0.1/32",),
    }
    values.update(overrides)
    return Settings(**values)


def invoke(app, method: str, path: str, **kwargs) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=app, client=("127.0.0.1", 32123))
        async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as client:
            return await client.request(method, path, **kwargs)

    return asyncio.run(run())


def test_passthrough_preserves_status_body_and_safe_application_headers():
    seen = {}

    def upstream(request: httpx.Request) -> httpx.Response:
        seen["request"] = request
        return httpx.Response(
            206,
            content=b"opaque-stock-body",
            headers={"Content-Type": "application/octet-stream", "Sid": "stock-sid"},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app = create_app(settings(), upstream_client=client)
    response = invoke(
        app,
        "POST",
        "/trafficRouter/cs?phase=1",
        content=b"stock-request",
        headers={
            "Ui": "living-room-device",
            "P": "[stock-sid]",
            "Cookie": "must-not-leave",
            "Authorization": "must-not-leave",
            "Connection": "upgrade",
        },
    )

    assert response.status_code == 206
    assert response.content == b"opaque-stock-body"
    assert response.headers["sid"] == "stock-sid"
    forwarded = seen["request"]
    assert str(forwarded.url) == "http://stock-upstream.invalid/trafficRouter/cs?phase=1"
    assert forwarded.headers["ui"] == "living-room-device"
    assert forwarded.headers["p"] == "[stock-sid]"
    assert "cookie" not in forwarded.headers
    assert "authorization" not in forwarded.headers
    # The HTTP client may add its own connection policy, but the device value
    # must never be relayed.
    assert forwarded.headers.get("connection") != "upgrade"

    asyncio.run(client.aclose())


def test_fixed_reply_rewrites_only_the_final_stock_answer():
    def upstream(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=FINAL_PAYLOAD,
            headers={"Content-Type": "application/json", "Sid": "stock-sid"},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app = create_app(
        settings(mode="fixed_reply", fixed_reply_text="固定答案已收到。"),
        upstream_client=client,
    )
    response = invoke(app, "POST", "/trafficRouter/cs", content=b"request")
    payload = response.json()

    assert response.status_code == 200
    assert response.headers["sid"] == "stock-sid"
    assert payload["general"]["text"] == "固定答案已收到。"
    assert payload["asr_recongize"] == FINAL_PAYLOAD["asr_recongize"]
    assert payload["responseId"] == FINAL_PAYLOAD["responseId"]
    assert payload["audioUrl"] == FINAL_PAYLOAD["audioUrl"]

    asyncio.run(client.aclose())


def test_fixed_reply_passes_intermediate_and_unknown_frames_through_unchanged():
    bodies = [
        b'{"asr_recongize":"partial"}',
        b"not-json",
        json.dumps(["unexpected", "shape"]).encode(),
    ]
    index = 0

    def upstream(_: httpx.Request) -> httpx.Response:
        nonlocal index
        body = bodies[index]
        index += 1
        return httpx.Response(200, content=body)

    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app = create_app(settings(mode="fixed_reply"), upstream_client=client)

    for body in bodies:
        response = invoke(app, "POST", "/trafficRouter/cs", content=b"request")
        assert response.content == body

    asyncio.run(client.aclose())


def test_fixed_reply_completes_twenty_independent_proxy_turns():
    calls = 0

    def upstream(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        payload = dict(FINAL_PAYLOAD, responseId=f"response-{calls:03d}")
        return httpx.Response(200, json=payload)

    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app = create_app(
        settings(mode="fixed_reply", fixed_reply_text="二十轮服务器回归。"),
        upstream_client=client,
    )

    for expected in range(1, 21):
        response = invoke(app, "POST", "/trafficRouter/cs", content=b"request")
        assert response.status_code == 200
        assert response.json()["responseId"] == f"response-{expected:03d}"
        assert response.json()["general"]["text"] == "二十轮服务器回归。"
    assert calls == 20

    asyncio.run(client.aclose())


def test_request_body_limit_rejects_before_contacting_upstream():
    calls = 0

    def upstream(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200)

    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app = create_app(
        settings(max_request_body_bytes=4),
        upstream_client=client,
    )
    response = invoke(app, "POST", "/trafficRouter/cs", content=b"12345")

    assert response.status_code == 413
    assert response.json() == {"error": "request_body_too_large"}
    assert calls == 0

    asyncio.run(client.aclose())


def test_unlisted_client_and_route_are_rejected():
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200)))
    blocked_app = create_app(
        settings(allowed_client_cidrs=("192.0.2.0/24",)),
        upstream_client=client,
    )
    assert invoke(blocked_app, "POST", "/trafficRouter/cs").status_code == 403
    assert invoke(blocked_app, "GET", "/healthz").status_code == 403

    allowed_app = create_app(settings(), upstream_client=client)
    assert invoke(allowed_app, "POST", "/proxy/http://example.com").status_code == 404
    assert invoke(allowed_app, "GET", "/healthz").json()["status"] == "ok"

    asyncio.run(client.aclose())


def test_upstream_error_is_bounded_and_does_not_leak_details():
    def upstream(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("secret upstream detail", request=request)

    client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app = create_app(settings(), upstream_client=client)
    response = invoke(app, "POST", "/trafficRouter/cs", content=b"private request")

    assert response.status_code == 502
    assert response.json() == {"error": "upstream_unavailable"}
    assert b"secret" not in response.content

    asyncio.run(client.aclose())


def test_structured_log_excludes_raw_device_id_and_body(caplog: pytest.LogCaptureFixture):
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=FINAL_PAYLOAD))
    )
    app = create_app(settings(mode="fixed_reply"), upstream_client=client)

    with caplog.at_level(logging.INFO, logger="stock_gateway"):
        response = invoke(
            app,
            "POST",
            "/trafficRouter/cs",
            content="私密正文".encode(),
            headers={"Ui": "raw-device-identifier"},
        )

    assert response.status_code == 200
    logs = "\n".join(caplog.messages)
    assert "raw-device-identifier" not in logs
    assert "私密正文" not in logs
    assert "event=request_complete" in logs

    asyncio.run(client.aclose())


@pytest.mark.parametrize(
    "overrides",
    [
        {"mode": "unknown"},
        {"upstream_base_url": "http://user:password@example.com"},
        {"upstream_base_url": "http://example.com/open-proxy"},
        {"allowed_client_cidrs": ()},
        {"fixed_reply_text": ""},
    ],
)
def test_unsafe_settings_are_rejected(overrides):
    with pytest.raises(ValueError):
        settings(**overrides)
