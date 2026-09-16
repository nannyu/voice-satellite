#!/usr/bin/env python3
"""Reference test server for the Voice Satellite protocol (docs/04, draft).

Doubles as (1) the protocol reference implementation future gateways are
measured against, and (2) an echo test server for the Android diagnostics
client. Protocol details follow docs/04-protocol.md and
android-r1/.../protocol/Protocol.java exactly:

- hello is validated (protocol version, device_id, device{model,
  client_version, platform}, capabilities); failures get an error frame
  (HANDSHAKE_FAILED / PROTOCOL_VERSION_UNSUPPORTED, recoverable=false) and a close.
- hello.ack carries protocol/session_id (uuid4) / heartbeat_seconds.
- ping -> pong; any inbound frame proves liveness; the server closes the
  connection after heartbeat_seconds + heartbeat_timeout of total silence
  (mirror of the Android HeartbeatMonitor dead-link rule).
- voice.start -> binary PCM frames -> voice.end -> state.processing ->
  response.start -> binary response frames (mode-dependent) ->
  response.end -> state.idle.
- Malformed frames and unknown types are dropped, never fatal.

Single active client by default: a second connection is rejected with a
SERVER_BUSY error and close code 1013 (try again later).

Python 3.8+, websockets 12.x. Logs are structured key=value lines; every
voice session ends with one SESSION summary line for acceptance statistics.
"""
import argparse
import asyncio
import json
import logging
import math
import time
import uuid

import websockets

PROTOCOL_VERSION = 1
HEARTBEAT_CHECK_SECONDS = 0.25
ECHO_CHUNK_BYTES = 960  # 30 ms of 16 kHz PCM16 mono, mirrors the Android frame size
TONE_SAMPLE_RATE = 16000
TONE_HZ = 440
TONE_SECONDS = 1.0
TONE_AMPLITUDE = 0.5
CLOSE_HEARTBEAT_TIMEOUT = 1001
CLOSE_BUSY = 1013
CLOSE_HANDSHAKE = 1002

LOG = logging.getLogger("voice-satellite-test-server")


class Config:
    def __init__(self, args):
        self.host = args.host
        self.port = args.port
        self.mode = args.mode
        self.heartbeat_seconds = args.heartbeat_seconds
        self.heartbeat_timeout_seconds = args.heartbeat_timeout_seconds
        self.hello_timeout_seconds = args.hello_timeout_seconds
        self.drop_after = args.drop_after
        self.no_ack = args.no_ack
        self.slow_response = args.slow_response


class VoiceSession:
    def __init__(self, session_id, trigger):
        self.session_id = session_id
        self.trigger = trigger
        self.pcm = bytearray()
        self.frames = 0
        self.started = time.monotonic()
        self.reason = None


class Connection:
    def __init__(self, websocket, config):
        self.websocket = websocket
        self.config = config
        self.session_id = None
        self.device_id = None
        self.last_inbound = time.monotonic()
        self.voice = None

    def log(self, message, **fields):
        parts = ["%s=%s" % (key, value) for key, value in sorted(fields.items())]
        LOG.info("%s %s", message, " ".join(parts))


async def send_json(connection, payload):
    await connection.websocket.send(json.dumps(payload))


async def send_error(connection, code, message, recoverable):
    await send_json(connection, {"type": "error", "code": code,
                                 "message": message, "recoverable": recoverable})


def hello_error(payload):
    """Returns the missing-field description, or an error code string, or None."""
    if not isinstance(payload, dict) or payload.get("type") != "hello":
        return "first frame must be a hello message"
    if payload.get("protocol") != PROTOCOL_VERSION:
        return "PROTOCOL_VERSION_UNSUPPORTED"
    if not payload.get("device_id"):
        return "missing field device_id"
    device = payload.get("device")
    if not isinstance(device, dict):
        return "missing field device"
    for field in ("model", "client_version", "platform"):
        if not device.get(field):
            return "missing field device.%s" % field
    if not isinstance(payload.get("capabilities"), list):
        return "missing field capabilities"
    return None


async def run_handshake(connection):
    config = connection.config
    raw = await asyncio.wait_for(connection.websocket.recv(),
                                 timeout=config.hello_timeout_seconds)
    connection.last_inbound = time.monotonic()
    try:
        payload = json.loads(raw) if isinstance(raw, str) else None
    except ValueError:
        payload = None
    problem = hello_error(payload)
    if problem == "PROTOCOL_VERSION_UNSUPPORTED":
        await send_error(connection, "PROTOCOL_VERSION_UNSUPPORTED",
                         "server speaks protocol %d" % PROTOCOL_VERSION, False)
        connection.log("HELLO_REJECTED", reason="protocol", got=payload.get("protocol"))
        return False
    if problem:
        await send_error(connection, "HANDSHAKE_FAILED", problem, False)
        connection.log("HELLO_REJECTED", reason=problem)
        return False
    connection.device_id = payload["device_id"]
    connection.session_id = uuid.uuid4().hex
    if not config.no_ack:
        await send_json(connection, {"type": "hello.ack", "protocol": PROTOCOL_VERSION,
                                     "session_id": connection.session_id,
                                     "heartbeat_seconds": config.heartbeat_seconds})
    connection.log("HELLO", device_id=connection.device_id,
                   session_id=connection.session_id, no_ack=config.no_ack)
    return True


def tone_pcm():
    """16 kHz mono PCM16 sine, matching PcmAudio.sineTone on the Android side."""
    samples = int(TONE_SAMPLE_RATE * TONE_SECONDS)
    pcm = bytearray(samples * 2)
    for i in range(samples):
        value = int(math.sin(2 * math.pi * TONE_HZ * i / TONE_SAMPLE_RATE)
                    * TONE_AMPLITUDE * 32767)
        pcm[i * 2] = value & 0xFF
        pcm[i * 2 + 1] = (value >> 8) & 0xFF
    return bytes(pcm)


def chunks(payload, size):
    return [payload[i:i + size] for i in range(0, len(payload), size)]


async def respond(connection):
    """voice.end received: state.processing -> response -> state.idle, plus the summary line."""
    config = connection.config
    voice = connection.voice
    connection.voice = None
    await send_json(connection, {"type": "state", "state": "processing"})
    if config.slow_response > 0:
        await asyncio.sleep(config.slow_response)
    if config.mode == "echo":
        payload = bytes(voice.pcm)
    elif config.mode == "tone":
        payload = tone_pcm()
    else:  # silence
        payload = b""
    await send_json(connection, {"type": "response.start", "session_id": voice.session_id})
    frames_out = chunks(payload, ECHO_CHUNK_BYTES)
    for frame in frames_out:
        await connection.websocket.send(frame)
    await send_json(connection, {"type": "response.end", "session_id": voice.session_id})
    await send_json(connection, {"type": "state", "state": "idle"})
    connection.log("SESSION", session_id=voice.session_id, trigger=voice.trigger,
                   frames=voice.frames, bytes=len(voice.pcm), reason=voice.reason,
                   duration_ms=int((time.monotonic() - voice.started) * 1000),
                   mode=config.mode, response_frames=len(frames_out),
                   response_bytes=len(payload))


async def handle_text(connection, text):
    try:
        payload = json.loads(text)
    except ValueError:
        connection.log("DROP_MALFORMED_TEXT", text=text[:80])
        return
    if not isinstance(payload, dict) or not payload.get("type"):
        connection.log("DROP_MALFORMED_TEXT", text=text[:80])
        return
    msg_type = payload["type"]
    if msg_type == "ping":
        await send_json(connection, {"type": "pong"})
    elif msg_type == "pong":
        pass  # liveness already recorded
    elif msg_type == "voice.start":
        session_id = payload.get("session_id")
        trigger = payload.get("trigger")
        if not session_id or not trigger:
            await send_error(connection, "INVALID_MESSAGE",
                             "voice.start requires session_id and trigger", True)
            return
        if connection.voice is not None:
            await send_error(connection, "INVALID_MESSAGE",
                             "voice.start while a voice session is active", True)
            return
        connection.voice = VoiceSession(session_id, trigger)
        connection.log("VOICE_START", session_id=session_id, trigger=trigger)
    elif msg_type == "voice.end":
        if connection.voice is None:
            await send_error(connection, "INVALID_MESSAGE",
                             "voice.end without an active voice session", True)
            return
        reason = payload.get("reason")
        if reason not in ("vad_end", "timeout", "cancelled", "error"):
            connection.log("VOICE_END_UNKNOWN_REASON", reason=reason)
        connection.voice.reason = reason
        await respond(connection)
    elif msg_type in ("state", "error"):
        connection.log("CLIENT_" + msg_type.upper(), payload=json.dumps(payload)[:120])
    else:
        connection.log("DROP_UNKNOWN_TYPE", type=msg_type)


async def heartbeat_watchdog(connection):
    config = connection.config
    limit = config.heartbeat_seconds + config.heartbeat_timeout_seconds
    while True:
        await asyncio.sleep(HEARTBEAT_CHECK_SECONDS)
        if time.monotonic() - connection.last_inbound > limit:
            connection.log("HEARTBEAT_TIMEOUT", session_id=connection.session_id,
                           limit_seconds=limit)
            await connection.websocket.close(CLOSE_HEARTBEAT_TIMEOUT, "heartbeat timeout")
            return


async def drop_task(connection):
    await asyncio.sleep(connection.config.drop_after)
    connection.log("FAULT_DROP", session_id=connection.session_id,
                   after_seconds=connection.config.drop_after)
    await connection.websocket.close(CLOSE_HEARTBEAT_TIMEOUT, "fault injection: drop-after")


async def handle_connection(websocket, server, path=None):
    config = server["config"]
    if server["active"] is not None:
        await websocket.send(json.dumps({"type": "error", "code": "SERVER_BUSY",
                                         "message": "test server accepts one client at a time",
                                         "recoverable": True}))
        await websocket.close(CLOSE_BUSY, "server busy")
        LOG.info("REJECT_BUSY")
        return
    connection = Connection(websocket, config)
    server["active"] = connection
    tasks = []
    try:
        if not await run_handshake(connection):
            await websocket.close(CLOSE_HANDSHAKE, "handshake failed")
            return
        tasks.append(asyncio.ensure_future(heartbeat_watchdog(connection)))
        if config.drop_after is not None:
            tasks.append(asyncio.ensure_future(drop_task(connection)))
        async for message in websocket:
            connection.last_inbound = time.monotonic()
            if isinstance(message, bytes):
                if connection.voice is not None:
                    connection.voice.pcm += message
                    connection.voice.frames += 1
                else:
                    connection.log("DROP_BINARY_OUTSIDE_SESSION", bytes=len(message))
            else:
                await handle_text(connection, message)
    except websockets.exceptions.ConnectionClosed:
        pass
    except asyncio.TimeoutError:
        LOG.warning("HELLO_TIMEOUT")
        try:
            await send_error(connection, "HANDSHAKE_FAILED", "hello timeout", False)
            await websocket.close(CLOSE_HANDSHAKE, "hello timeout")
        except websockets.exceptions.ConnectionClosed:
            pass
    finally:
        for task in tasks:
            task.cancel()
        server["active"] = None
        connection.log("CLOSE", session_id=connection.session_id,
                       device_id=connection.device_id)


async def serve(config):
    server = {"config": config, "active": None}
    async with websockets.serve(lambda ws, path=None: handle_connection(ws, server, path),
                                config.host, config.port):
        LOG.info("LISTENING host=%s port=%d mode=%s heartbeat_seconds=%d",
                 config.host, config.port, config.mode, config.heartbeat_seconds)
        await asyncio.Future()  # run forever


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--mode", choices=("echo", "tone", "silence"), default="echo",
                        help="echo: return captured PCM verbatim; tone: 440 Hz 1 s sine; "
                             "silence: control messages only")
    parser.add_argument("--heartbeat-seconds", type=int, default=30,
                        help="advertised in hello.ack; server also allows this much silence")
    parser.add_argument("--heartbeat-timeout-seconds", type=float, default=10,
                        help="extra silence beyond heartbeat_seconds before the server "
                             "declares the link dead (mirrors the Android heartbeat timeout)")
    parser.add_argument("--hello-timeout-seconds", type=float, default=10)
    parser.add_argument("--drop-after", type=float, default=None,
                        help="fault injection: close the connection N seconds after hello.ack")
    parser.add_argument("--no-ack", action="store_true",
                        help="fault injection: accept hello but never send hello.ack")
    parser.add_argument("--slow-response", type=float, default=0,
                        help="fault injection: delay N seconds between voice.end and "
                             "state.processing")
    return parser.parse_args(argv)


def main():
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    try:
        asyncio.run(serve(Config(parse_args())))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
