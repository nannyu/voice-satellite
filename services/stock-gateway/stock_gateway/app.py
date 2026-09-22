from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import hashlib
import ipaddress
import logging
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
import httpx

from . import __version__
from .config import Settings
from .protocol import (
    STOCK_ROUTE_PATH,
    build_forward_headers,
    build_response_headers,
    rewrite_fixed_reply,
)


LOG = logging.getLogger("stock_gateway")


def _client_allowed(host: str, settings: Settings) -> bool:
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return any(address in network for network in settings.client_networks)


def _device_tag(raw: str | None) -> str:
    if not raw:
        return "unknown"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]


async def _read_limited_request(request: Request, limit: int) -> bytes:
    declared = request.headers.get("content-length")
    if declared:
        try:
            declared_size = int(declared)
            if declared_size < 0:
                raise ValueError
            if declared_size > limit:
                raise OverflowError
        except ValueError as exc:
            raise ValueError("invalid Content-Length") from exc

    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > limit:
            raise OverflowError
    return bytes(body)


async def _read_limited_response(response: httpx.Response, limit: int) -> bytes:
    # MockTransport and some custom transports return an already-buffered
    # response even when the caller requested streaming.
    if response.is_stream_consumed:
        if len(response.content) > limit:
            raise OverflowError
        return response.content

    body = bytearray()
    async for chunk in response.aiter_raw():
        body.extend(chunk)
        if len(body) > limit:
            raise OverflowError
    return bytes(body)


def create_app(
    settings: Settings | None = None,
    *,
    upstream_client: httpx.AsyncClient | None = None,
) -> FastAPI:
    runtime = settings or Settings.from_env()
    owned_client = upstream_client is None
    client = upstream_client or httpx.AsyncClient(
        timeout=httpx.Timeout(runtime.upstream_timeout_seconds),
        follow_redirects=False,
    )
    slots = asyncio.Semaphore(runtime.max_in_flight)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        if owned_client:
            await client.aclose()

    app = FastAPI(
        title="R1 Stock Gateway",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )

    @app.get("/healthz")
    async def healthz(request: Request) -> Response:
        client_host = request.client.host if request.client else ""
        if not _client_allowed(client_host, runtime):
            return JSONResponse(status_code=403, content={"error": "client_not_allowed"})
        return JSONResponse(
            content={"status": "ok", "mode": runtime.mode, "version": __version__}
        )

    @app.post(STOCK_ROUTE_PATH)
    async def stock_route(request: Request) -> Response:
        started = time.monotonic()
        request_id = uuid.uuid4().hex
        client_host = request.client.host if request.client else ""
        device_tag = _device_tag(request.headers.get("ui"))

        if not _client_allowed(client_host, runtime):
            LOG.warning(
                "event=request_rejected request_id=%s reason=client_not_allowed device=%s",
                request_id,
                device_tag,
            )
            return JSONResponse(status_code=403, content={"error": "client_not_allowed"})

        try:
            body = await _read_limited_request(request, runtime.max_request_body_bytes)
        except ValueError:
            return JSONResponse(status_code=400, content={"error": "invalid_content_length"})
        except OverflowError:
            return JSONResponse(status_code=413, content={"error": "request_body_too_large"})

        try:
            await asyncio.wait_for(slots.acquire(), timeout=runtime.queue_timeout_seconds)
        except TimeoutError:
            return JSONResponse(status_code=503, content={"error": "gateway_busy"})

        rewritten = False
        status_code = 502
        try:
            query = f"?{request.url.query}" if request.url.query else ""
            target = f"{runtime.normalized_upstream_base_url}{STOCK_ROUTE_PATH}{query}"
            headers = build_forward_headers(
                request.headers,
                force_identity=runtime.mode == "fixed_reply",
            )
            async with client.stream(
                "POST",
                target,
                content=body,
                headers=headers,
                timeout=runtime.upstream_timeout_seconds,
            ) as upstream:
                try:
                    upstream_body = await _read_limited_response(
                        upstream, runtime.max_response_body_bytes
                    )
                except OverflowError:
                    return JSONResponse(
                        status_code=502, content={"error": "upstream_response_too_large"}
                    )

                if runtime.mode == "fixed_reply":
                    result = rewrite_fixed_reply(upstream_body, runtime.fixed_reply_text)
                    upstream_body = result.body
                    rewritten = result.rewritten

                status_code = upstream.status_code
                response_headers = build_response_headers(upstream.headers.multi_items())
                return Response(
                    content=upstream_body,
                    status_code=upstream.status_code,
                    headers=response_headers,
                )
        except httpx.TimeoutException:
            status_code = 504
            return JSONResponse(status_code=504, content={"error": "upstream_timeout"})
        except httpx.RequestError:
            status_code = 502
            return JSONResponse(status_code=502, content={"error": "upstream_unavailable"})
        finally:
            slots.release()
            LOG.info(
                "event=request_complete request_id=%s mode=%s status=%d rewritten=%s "
                "device=%s request_bytes=%d duration_ms=%d",
                request_id,
                runtime.mode,
                status_code,
                str(rewritten).lower(),
                device_tag,
                len(body),
                int((time.monotonic() - started) * 1000),
            )

    return app


app = create_app()
