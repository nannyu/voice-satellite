"""End-to-end tests for gateway/test_server.py against the docs/04 draft protocol.

Each test launches the real server as a subprocess on an ephemeral port and
drives it with a websockets client, mirroring the Android client behavior
(protocol/Protocol.java + transport/HeartbeatMonitor). Run with:

    python -m pytest gateway/test_protocol.py -v
"""
import asyncio
from contextlib import contextmanager
import json
from pathlib import Path
import socket
import subprocess
import sys
import time

import pytest
import websockets

SERVER = Path(__file__).resolve().parent / "test_server.py"
RECV_TIMEOUT = 5

HELLO = {"type": "hello", "protocol": 1, "device_id": "pytest-device",
         "device": {"model": "phicomm-r1", "client_version": "0.2.0-dev",
                    "platform": "android-5.1"},
         "capabilities": ["audio.pcm16", "wake.local"]}


def free_port():
    probe = socket.socket()
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    return port


@contextmanager
def running_server(*args):
    port = free_port()
    proc = subprocess.Popen(
        [sys.executable, str(SERVER), "--host", "127.0.0.1", "--port", str(port)] + list(args),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        deadline = time.monotonic() + 10
        while True:
            if proc.poll() is not None:
                raise RuntimeError("server exited early:\n" + (proc.stdout.read() or ""))
            try:
                socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
                break
            except OSError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.1)
        yield "ws://127.0.0.1:%d" % port
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


async def recv_json(ws, timeout=RECV_TIMEOUT):
    return json.loads(await asyncio.wait_for(ws.recv(), timeout))


async def do_hello(ws, hello=HELLO):
    await ws.send(json.dumps(hello))
    return await recv_json(ws)


async def voice_session(ws, session_id, payload=b"", reason="vad_end", trigger="wake_word"):
    """Runs one voice session after handshake; returns the echoed binary payload."""
    await ws.send(json.dumps({"type": "voice.start", "session_id": session_id,
                              "trigger": trigger,
                              "audio": {"codec": "pcm_s16le", "sample_rate": 16000,
                                        "channels": 1}}))
    for i in range(0, len(payload), 960):
        await ws.send(payload[i:i + 960])
    await ws.send(json.dumps({"type": "voice.end", "session_id": session_id,
                              "reason": reason}))
    received = bytearray()
    events = []
    while True:
        frame = await asyncio.wait_for(ws.recv(), RECV_TIMEOUT)
        if isinstance(frame, bytes):
            received += frame
            continue
        message = json.loads(frame)
        events.append(message)
        if message.get("type") == "state" and message.get("state") == "idle":
            return bytes(received), events


def event_types(events):
    return [e["type"] + (":" + e["state"] if e["type"] == "state" else "") for e in events]


def test_echo_session_roundtrips_exact_bytes():
    with running_server("--mode", "echo") as url:
        async def go():
            async with websockets.connect(url) as ws:
                ack = await do_hello(ws)
                assert ack["type"] == "hello.ack"
                assert ack["protocol"] == 1
                assert ack["heartbeat_seconds"] == 30
                assert ack["session_id"]

                payload = bytes(range(256)) * 40  # 10240 bytes, non-frame-aligned tail
                echoed, events = await voice_session(ws, "v-1", payload)
                assert echoed == payload
                assert event_types(events) == ["state:processing", "response.start",
                                               "response.end", "state:idle"]
        asyncio.run(go())


def test_repeated_sessions_on_one_connection():
    with running_server("--mode", "echo") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                for i in range(3):
                    payload = (b"\x5a\xff" * 480) * (i + 1)
                    echoed, _ = await voice_session(ws, "v-%d" % i, payload)
                    assert echoed == payload
        asyncio.run(go())


@pytest.mark.parametrize("reason", ["vad_end", "timeout", "cancelled", "error"])
def test_voice_end_reasons_are_accepted(reason):
    with running_server("--mode", "echo") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                echoed, events = await voice_session(ws, "v-" + reason, b"\x00\x01" * 480,
                                                    reason=reason)
                assert echoed == b"\x00\x01" * 480
                assert event_types(events)[-1] == "state:idle"
        asyncio.run(go())


def test_tone_mode_returns_generated_sine():
    with running_server("--mode", "tone") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                echoed, _ = await voice_session(ws, "v-tone", b"")
                assert len(echoed) == 32000  # 1 s of 16 kHz PCM16 mono
                peak = max(abs(int.from_bytes(echoed[i:i + 2], "little", signed=True))
                           for i in range(0, len(echoed), 2))
                assert 10000 < peak <= 32767 // 2 + 1
        asyncio.run(go())


def test_silence_mode_returns_control_messages_only():
    with running_server("--mode", "silence") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                echoed, events = await voice_session(ws, "v-sil", b"\x01" * 960)
                assert echoed == b""
                assert event_types(events) == ["state:processing", "response.start",
                                               "response.end", "state:idle"]
        asyncio.run(go())


def test_hello_missing_field_is_rejected():
    with running_server() as url:
        async def go():
            async with websockets.connect(url) as ws:
                bad = dict(HELLO)
                del bad["device_id"]
                await ws.send(json.dumps(bad))
                error = await recv_json(ws)
                assert error["type"] == "error"
                assert error["code"] == "HANDSHAKE_FAILED"
                assert error["recoverable"] is False
                assert "device_id" in error["message"]
                await asyncio.wait_for(ws.wait_closed(), RECV_TIMEOUT)
        asyncio.run(go())


def test_hello_wrong_protocol_version_is_rejected():
    with running_server() as url:
        async def go():
            async with websockets.connect(url) as ws:
                bad = dict(HELLO, protocol=99)
                await ws.send(json.dumps(bad))
                error = await recv_json(ws)
                assert error["code"] == "PROTOCOL_VERSION_UNSUPPORTED"
                assert error["recoverable"] is False
                await asyncio.wait_for(ws.wait_closed(), RECV_TIMEOUT)
        asyncio.run(go())


def test_unknown_type_and_malformed_frames_are_tolerated():
    with running_server() as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                await ws.send(json.dumps({"type": "media.play", "request_id": "u"}))
                await ws.send("this is not json")
                await ws.send(b"\x00\x01\x02")  # binary outside a voice session
                await ws.send(json.dumps({"type": "ping", "session_id": "x"}))
                assert (await recv_json(ws)) == {"type": "pong"}
        asyncio.run(go())


def test_ping_gets_pong():
    with running_server() as url:
        async def go():
            async with websockets.connect(url) as ws:
                ack = await do_hello(ws)
                await ws.send(json.dumps({"type": "ping",
                                          "session_id": ack["session_id"]}))
                assert (await recv_json(ws)) == {"type": "pong"}
        asyncio.run(go())


def test_server_closes_dead_connection_after_heartbeat_silence():
    with running_server("--heartbeat-seconds", "1",
                        "--heartbeat-timeout-seconds", "1") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                # No ping, no traffic: server must declare the link dead and close.
                await asyncio.wait_for(ws.wait_closed(), 10)
        asyncio.run(go())


def test_inbound_frames_keep_the_connection_alive():
    with running_server("--heartbeat-seconds", "1",
                        "--heartbeat-timeout-seconds", "1") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                for _ in range(5):
                    await asyncio.sleep(0.6)
                    await ws.send(json.dumps({"type": "ping", "session_id": "x"}))
                    assert (await recv_json(ws)) == {"type": "pong"}
        asyncio.run(go())


def test_drop_after_disconnects_and_reconnect_works():
    with running_server("--drop-after", "1") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                await asyncio.wait_for(ws.wait_closed(), 10)
            async with websockets.connect(url) as ws:
                ack = await do_hello(ws)
                assert ack["type"] == "hello.ack"
        asyncio.run(go())


def test_no_ack_never_answers_hello():
    with running_server("--no-ack") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await ws.send(json.dumps(HELLO))
                with pytest.raises(asyncio.TimeoutError):
                    await asyncio.wait_for(ws.recv(), 2)
        asyncio.run(go())


def test_slow_response_delays_processing_state():
    with running_server("--slow-response", "1") as url:
        async def go():
            async with websockets.connect(url) as ws:
                await do_hello(ws)
                started = time.monotonic()
                await voice_session(ws, "v-slow", b"\x00" * 2)
                assert time.monotonic() - started >= 1.0
        asyncio.run(go())


def test_second_client_is_rejected_busy():
    with running_server() as url:
        async def go():
            async with websockets.connect(url) as first:
                await do_hello(first)
                async with websockets.connect(url) as second:
                    error = await recv_json(second)
                    assert error["code"] == "SERVER_BUSY"
                    assert error["recoverable"] is True
                    await asyncio.wait_for(second.wait_closed(), RECV_TIMEOUT)
                # First client is unaffected.
                await first.send(json.dumps({"type": "ping", "session_id": "x"}))
                assert (await recv_json(first)) == {"type": "pong"}
        asyncio.run(go())
