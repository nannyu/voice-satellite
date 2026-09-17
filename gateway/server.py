#!/usr/bin/env python3
"""Voice Satellite Opus MVP gateway: decode -> bounded PCM backend -> encode.

The built-in backends are echo, tone and silence. This is an audio transport MVP,
not an STT/LLM/TTS or Home Assistant adapter. Audio is not persisted or logged.
"""
import argparse
import asyncio
import hmac
import json
import logging
import math
import os
import re
import struct
import time
import uuid
from dataclasses import dataclass

from websockets.exceptions import ConnectionClosed
from websockets.legacy.server import serve
from opus_codec import (AudioError, Decoder, Encoder, FORMAT, FRAME_SAMPLES, LibOpus,
                        MAX_INPUT_SAMPLES, MAX_OUTPUT_SAMPLES, integer, require_format, trim)

LOG = logging.getLogger("voice-gateway")
ID = re.compile(r"^[A-Za-z0-9_-]{1,128}$")


@dataclass
class Config:
    host: str = "127.0.0.1"
    port: int = 8765
    token: str = ""
    hello_timeout: float = 10.0
    heartbeat_seconds: int = 5
    heartbeat_timeout: float = 10.0
    utterance_timeout: float = 20.0
    backend_timeout: float = 10.0
    response_timeout: float = 10.0


class AudioBackend:
    """Replace this boundary with a future HA/agent pipeline returning PCM16/16 kHz/mono."""
    async def respond(self, pcm):
        raise NotImplementedError


class EchoBackend(AudioBackend):
    async def respond(self, pcm):
        return pcm


class ToneBackend(AudioBackend):
    async def respond(self, pcm):
        return b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / 16000)))
                        for i in range(16000))


class SilenceBackend(AudioBackend):
    async def respond(self, pcm):
        return b""


class ProtocolError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def object_message(raw):
    if not isinstance(raw, str):
        raise ProtocolError("INVALID_MESSAGE", "expected JSON control frame")
    try:
        value = json.loads(raw)
    except (ValueError, RecursionError) as exc:
        raise ProtocolError("INVALID_MESSAGE", "invalid JSON") from exc
    if not isinstance(value, dict) or not isinstance(value.get("type"), str):
        raise ProtocolError("INVALID_MESSAGE", "type string required")
    return value


class Voice:
    def __init__(self, api, payload):
        self.id = payload.get("session_id")
        if not isinstance(self.id, str) or not ID.fullmatch(self.id):
            raise ProtocolError("INVALID_MESSAGE", "invalid turn id")
        if payload.get("trigger") not in ("button", "wake_word"):
            raise ProtocolError("INVALID_MESSAGE", "invalid trigger")
        require_format(payload.get("audio"))
        self.pre_skip = integer(payload, "pre_skip", FRAME_SAMPLES)
        self.decoder = Decoder(api)
        self.pcm = bytearray()
        self.started = time.monotonic()
        self.frames = self.wire_bytes = 0

    def append(self, packet):
        decoded = self.decoder.decode(packet)
        if len(self.pcm) + len(decoded) > (MAX_INPUT_SAMPLES + 2 * FRAME_SAMPLES) * 2:
            raise ProtocolError("AUDIO_TOO_LARGE", "utterance exceeds 15 seconds")
        self.pcm.extend(decoded)
        self.frames += 1
        self.wire_bytes += len(packet)

    def finish(self, payload):
        if payload.get("session_id") != self.id:
            raise ProtocolError("INVALID_MESSAGE", "voice.end turn mismatch")
        if payload.get("reason") not in ("vad_end", "timeout"):
            raise ProtocolError("INVALID_MESSAGE", "unexpected voice.end reason")
        samples = integer(payload, "samples", MAX_INPUT_SAMPLES)
        if samples == 0:
            raise ProtocolError("EMPTY_AUDIO", "utterance is empty")
        return trim(self.pcm, self.pre_skip, samples, MAX_INPUT_SAMPLES)

    def close(self):
        self.decoder.close()
        self.pcm.clear()


class Connection:
    def __init__(self, gateway, socket):
        self.gateway, self.socket = gateway, socket
        self.config, self.api = gateway.config, gateway.api
        self.session_id = uuid.uuid4().hex
        self.last_inbound = time.monotonic()
        self.voice = None
        self.response_task = None
        self.stopping = False

    async def send(self, payload):
        await self.socket.send(json.dumps(payload, separators=(",", ":")))

    async def reject(self, code, message, recoverable=False):
        if self.stopping:
            return
        self.stopping = True
        try:
            await self.send({"type": "error", "code": code, "message": message,
                             "recoverable": recoverable})
        finally:
            await self.socket.close(1002, code[:100])

    async def handshake(self):
        raw = await asyncio.wait_for(self.socket.recv(), self.config.hello_timeout)
        payload = object_message(raw)
        if payload.get("type") != "hello" or type(payload.get("protocol")) is not int or payload["protocol"] != 1:
            raise ProtocolError("PROTOCOL_VERSION_UNSUPPORTED", "protocol 1 hello required")
        if self.config.token:
            supplied = payload.get("token")
            if not isinstance(supplied, str) or not hmac.compare_digest(
                    supplied.encode("utf-8"), self.config.token.encode("utf-8")):
                raise ProtocolError("UNAUTHORIZED", "invalid gateway token")
        if not isinstance(payload.get("device_id"), str) or not ID.fullmatch(payload["device_id"]):
            raise ProtocolError("HANDSHAKE_FAILED", "invalid device_id")
        device = payload.get("device")
        if not isinstance(device, dict) or any(not isinstance(device.get(key), str) or not device[key]
                                              for key in ("model", "client_version", "platform")):
            raise ProtocolError("HANDSHAKE_FAILED", "missing device identity")
        capabilities = payload.get("capabilities")
        if not isinstance(capabilities, list) or "opus" not in capabilities:
            raise ProtocolError("UNSUPPORTED_AUDIO", "opus capability is required")
        require_format(payload.get("audio"))
        self.last_inbound = time.monotonic()
        await self.send({"type": "hello.ack", "protocol": 1, "session_id": self.session_id,
                         "heartbeat_seconds": self.config.heartbeat_seconds, "audio": FORMAT})

    async def control(self, payload):
        kind = payload["type"]
        if kind == "ping":
            await self.send({"type": "pong", "session_id": self.session_id})
        elif kind == "pong":
            pass
        elif kind == "voice.start":
            if self.voice is not None or (self.response_task is not None and not self.response_task.done()):
                raise ProtocolError("BUSY", "only one utterance may be active")
            self.voice = Voice(self.api, payload)
        elif kind == "voice.end":
            if self.voice is None:
                raise ProtocolError("INVALID_MESSAGE", "voice.end without voice.start")
            voice = self.voice
            try:
                pcm = voice.finish(payload)
            finally:
                self.voice = None
                voice.close()
            self.response_task = asyncio.create_task(self.respond(voice, pcm))
        elif kind == "hello":
            raise ProtocolError("INVALID_MESSAGE", "duplicate hello")
        # Unknown extension control types are dropped; malformed known controls fail closed.

    async def respond(self, voice, pcm):
        try:
            await self.send({"type": "state", "state": "processing", "session_id": voice.id})
            output = await asyncio.wait_for(self.gateway.backend.respond(pcm), self.config.backend_timeout)
            if not isinstance(output, bytes) or len(output) % 2 or len(output) > MAX_OUTPUT_SAMPLES * 2:
                raise ProtocolError("BACKEND_AUDIO_INVALID", "backend must return bounded PCM16 bytes")
            with Encoder(self.api) as encoder:
                packets = encoder.packets(output)
                skip = encoder.pre_skip if output else 0
            async def transmit():
                await self.send({"type": "response.start", "session_id": voice.id,
                                 "audio": FORMAT, "pre_skip": skip})
                for packet in packets:
                    await self.socket.send(packet)
                await self.send({"type": "response.end", "session_id": voice.id,
                                 "samples": len(output) // 2})
                await self.send({"type": "state", "state": "idle", "session_id": voice.id})
            await asyncio.wait_for(transmit(), self.config.response_timeout)
            LOG.info("SESSION turn=%s input_samples=%d input_packets=%d input_wire_bytes=%d "
                     "output_samples=%d output_packets=%d output_wire_bytes=%d elapsed_ms=%d",
                     voice.id, len(pcm) // 2, voice.frames, voice.wire_bytes, len(output) // 2,
                     len(packets), sum(map(len, packets)), int((time.monotonic() - voice.started) * 1000))
        except ConnectionClosed:
            pass
        except asyncio.TimeoutError:
            await self.reject("RESPONSE_TIMEOUT", "backend or response deadline exceeded", True)
        except (AudioError, ProtocolError) as exc:
            await self.reject(getattr(exc, "code", "INVALID_AUDIO"), str(exc), True)
        except Exception:
            LOG.error("backend failure", exc_info=False)
            await self.reject("BACKEND_FAILED", "backend failed", True)

    async def watchdog(self):
        while True:
            await asyncio.sleep(0.1)
            now = time.monotonic()
            if self.voice and now - self.voice.started > self.config.utterance_timeout:
                await self.reject("UTTERANCE_TIMEOUT", "voice.end deadline exceeded", True)
                return
            if now - self.last_inbound > self.config.heartbeat_seconds + self.config.heartbeat_timeout:
                await self.reject("HEARTBEAT_TIMEOUT", "inbound heartbeat deadline exceeded", True)
                return

    async def run(self):
        watchdog = None
        try:
            await self.handshake()
            watchdog = asyncio.create_task(self.watchdog())
            async for raw in self.socket:
                self.last_inbound = time.monotonic()
                if isinstance(raw, bytes):
                    if self.voice is None:
                        raise ProtocolError("INVALID_MESSAGE", "audio outside an utterance")
                    self.voice.append(raw)
                else:
                    await self.control(object_message(raw))
        except ConnectionClosed:
            pass
        except asyncio.TimeoutError:
            await self.reject("HANDSHAKE_TIMEOUT", "hello deadline exceeded", True)
        except (AudioError, ProtocolError) as exc:
            await self.reject(getattr(exc, "code", "INVALID_AUDIO"), str(exc))
        finally:
            tasks = [task for task in (watchdog, self.response_task) if task is not None]
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            if self.voice:
                self.voice.close()
                self.voice = None


class Gateway:
    """One active satellite for the MVP; a socket disconnect always releases the slot."""
    def __init__(self, config=None, backend=None, api=None):
        self.config = config or Config()
        self.backend = backend or EchoBackend()
        self.api = api or LibOpus()  # Fail at startup rather than at the first utterance.
        self.active = None

    async def __call__(self, socket, path=None):
        if self.active is not None:
            await socket.send(json.dumps({"type": "error", "code": "SERVER_BUSY",
                                          "message": "one active satellite allowed", "recoverable": True}))
            await socket.close(1013, "server busy")
            return
        connection = Connection(self, socket)
        self.active = connection
        try:
            await connection.run()
        finally:
            if self.active is connection:
                self.active = None

    def listen(self):
        return serve(self, self.config.host, self.config.port, max_size=65536, max_queue=8,
                     read_limit=65536, write_limit=65536, compression=None, ping_interval=None,
                     close_timeout=1, origins=[None])


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--mode", choices=("echo", "tone", "silence"), default="echo")
    parser.add_argument("--allow-insecure-lan", action="store_true",
                        help="explicitly allow unauthenticated LAN diagnostics; never expose to the Internet")
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("port must be within 0..65535")
    token = os.environ.get("VOICE_GATEWAY_TOKEN", "")
    if args.host not in ("127.0.0.1", "::1", "localhost") and not token and not args.allow_insecure_lan:
        parser.error("non-loopback bind requires VOICE_GATEWAY_TOKEN or --allow-insecure-lan")
    return args, token


async def main_async(args, token):
    backend = {"echo": EchoBackend, "tone": ToneBackend, "silence": SilenceBackend}[args.mode]()
    gateway = Gateway(Config(host=args.host, port=args.port, token=token), backend)
    async with gateway.listen() as server:
        port = server.sockets[0].getsockname()[1]
        LOG.info("LISTENING host=%s port=%d mode=%s auth=%s", args.host, port, args.mode, bool(token))
        await asyncio.Future()


def main():
    args, token = parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if args.allow_insecure_lan:
        LOG.warning("Unauthenticated LAN diagnostics enabled; do not expose this service to the Internet")
    try:
        asyncio.run(main_async(args, token))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
