"""Real-libopus unit and loopback WebSocket tests; no R1 hardware is impersonated."""
import asyncio
import json
import math
import struct
import unittest

from websockets.exceptions import ConnectionClosed
from websockets.legacy.client import connect
from opus_codec import (AudioError, Decoder, Encoder, FORMAT, FRAME_SAMPLES, LibOpus,
                        MAX_INPUT_SAMPLES, MAX_OUTPUT_SAMPLES, require_format, trim)
from server import Config, Gateway, EchoBackend, ToneBackend, SilenceBackend, AudioBackend


def signal(samples=16800):
    return b"".join(struct.pack("<h", int(7000 * math.sin(i * 2 * math.pi * 440 / 16000)))
                    for i in range(samples))


def hello(token=None):
    value = {"type": "hello", "protocol": 1, "device_id": "test-device",
             "device": {"model": "test", "client_version": "test", "platform": "host"},
             "capabilities": ["opus"], "audio": dict(FORMAT)}
    if token is not None:
        value["token"] = token
    return value


class CodecTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api = LibOpus()

    def test_real_codec_duration_and_energy(self):
        pcm = signal(16817)
        with Encoder(self.api) as encoder, Decoder(self.api) as decoder:
            packets = encoder.packets(pcm)
            decoded = b"".join(decoder.decode(packet) for packet in packets)
            result = trim(decoded, encoder.pre_skip, len(pcm) // 2, MAX_OUTPUT_SAMPLES)
        self.assertEqual(len(result), len(pcm))
        self.assertLess(sum(map(len, packets)), len(pcm))
        values = struct.unpack("<%dh" % (len(result) // 2), result)
        self.assertGreater(sum(x * x for x in values) / len(values), 100000)

    def test_empty_stream(self):
        with Encoder(self.api) as encoder:
            self.assertEqual(encoder.packets(b""), [])
        self.assertEqual(trim(b"", 0, 0, 0), b"")

    def test_closed_codec_and_bad_sizes(self):
        encoder, decoder = Encoder(self.api), Decoder(self.api)
        for size in (0, 1, 960, 1922):
            with self.assertRaises(AudioError):
                encoder.encode(bytes(size))
        for data in (b"", bytes(4001), b"\xff"):
            with self.assertRaises(AudioError):
                decoder.decode(data)
        encoder.close(); decoder.close()
        encoder.close(); decoder.close()
        with self.assertRaises(AudioError):
            encoder.encode(bytes(1920))
        with self.assertRaises(AudioError):
            decoder.decode(b"\xf8\xff\xfe")

    def test_trim_rejects_missing_extra_or_fractional_samples(self):
        for skip, samples, decoded in ((0, 961, bytes(1920)), (0, 0, bytes(1920)),
                                       (104, 961, bytes(1920)), (0, 1.0, bytes(1920))):
            with self.assertRaises(AudioError):
                trim(decoded, skip, samples, MAX_OUTPUT_SAMPLES)

    def test_format_does_not_coerce_values(self):
        for key, value in (("sample_rate", "16000"), ("sample_rate", 16000.0),
                           ("channels", True), ("frame_ms", 30), ("codec", "pcm_s16le")):
            payload = dict(FORMAT, **{key: value})
            with self.assertRaises(AudioError):
                require_format(payload)


class GatewayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.api = LibOpus()
        self.gateway = Gateway(Config(port=0), api=self.api)
        self.server = await self.gateway.listen()
        self.url = "ws://127.0.0.1:%d" % self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        self.assertIsNone(self.gateway.active)

    async def open(self, payload=None):
        socket = await connect(self.url, ping_interval=None, compression=None)
        await socket.send(json.dumps(payload or hello()))
        return socket

    async def receive_json(self, socket):
        return json.loads(await asyncio.wait_for(socket.recv(), 3))

    async def start(self, socket, encoder, turn="turn-1"):
        await socket.send(json.dumps({"type": "voice.start", "session_id": turn, "trigger": "button",
                                      "audio": FORMAT, "pre_skip": encoder.pre_skip}))

    async def upload(self, socket, pcm, turn="turn-1"):
        with Encoder(self.api) as encoder:
            await self.start(socket, encoder, turn)
            packets = encoder.packets(pcm)
            for packet in packets:
                await socket.send(packet)
        await socket.send(json.dumps({"type": "voice.end", "session_id": turn,
                                      "reason": "vad_end", "samples": len(pcm) // 2}))

    async def response(self, socket):
        pcm = bytearray()
        skip = samples = None
        with Decoder(self.api) as decoder:
            while True:
                raw = await asyncio.wait_for(socket.recv(), 3)
                if isinstance(raw, bytes):
                    self.assertIsNotNone(skip)
                    pcm.extend(decoder.decode(raw))
                    continue
                payload = json.loads(raw)
                self.assertNotEqual(payload["type"], "error", payload)
                if payload["type"] == "response.start":
                    require_format(payload["audio"])
                    skip = payload["pre_skip"]
                elif payload["type"] == "response.end":
                    samples = payload["samples"]
                elif payload.get("state") == "idle":
                    break
        return trim(pcm, skip, samples, MAX_OUTPUT_SAMPLES)

    async def test_real_websocket_echo_two_turns(self):
        socket = await self.open()
        try:
            self.assertEqual((await self.receive_json(socket))["audio"], FORMAT)
            pcm = signal(16817)
            for turn in ("turn-1", "turn-2"):
                await self.upload(socket, pcm, turn)
                result = await self.response(socket)
                self.assertEqual(len(result), len(pcm))
                values = struct.unpack("<%dh" % (len(result) // 2), result)
                self.assertGreater(sum(x * x for x in values) / len(values), 100000)
        finally:
            await socket.close()

    async def test_tone_and_silence_backends(self):
        socket = await self.open()
        try:
            await self.receive_json(socket)
            for backend, samples in ((ToneBackend(), 16000), (SilenceBackend(), 0)):
                self.gateway.backend = backend
                await self.upload(socket, signal(1920))
                self.assertEqual(len(await self.response(socket)), samples * 2)
        finally:
            await socket.close()

    async def test_negotiation_rejects_legacy_pcm(self):
        payload = hello()
        payload["audio"]["codec"] = "pcm_s16le"
        socket = await self.open(payload)
        try:
            self.assertEqual((await self.receive_json(socket))["type"], "error")
        finally:
            await socket.close()

    async def test_token_rejection_and_success(self):
        self.gateway.config.token = "unit-test-token"
        socket = await self.open()
        self.assertEqual((await self.receive_json(socket))["code"], "UNAUTHORIZED")
        await socket.close()
        for _ in range(100):
            if self.gateway.active is None:
                break
            await asyncio.sleep(.001)
        socket = await self.open(hello("unit-test-token"))
        self.assertEqual((await self.receive_json(socket))["type"], "hello.ack")
        await socket.close()

    async def test_binary_before_voice_start(self):
        socket = await self.open()
        await self.receive_json(socket)
        await socket.send(b"bad")
        self.assertEqual((await self.receive_json(socket))["code"], "INVALID_MESSAGE")
        await socket.close()

    async def test_wrong_turn_id_on_end(self):
        socket = await self.open()
        await self.receive_json(socket)
        with Encoder(self.api) as encoder:
            await self.start(socket, encoder)
            for packet in encoder.packets(signal(480)):
                await socket.send(packet)
        await socket.send(json.dumps({"type": "voice.end", "session_id": "other",
                                      "reason": "vad_end", "samples": 480}))
        self.assertEqual((await self.receive_json(socket))["code"], "INVALID_MESSAGE")
        await socket.close()

    async def test_oversized_packet(self):
        socket = await self.open()
        await self.receive_json(socket)
        with Encoder(self.api) as encoder:
            await self.start(socket, encoder)
        await socket.send(bytes(4001))
        self.assertEqual((await self.receive_json(socket))["code"], "INVALID_AUDIO")
        await socket.close()

    async def test_pcm_duration_limit(self):
        socket = await self.open()
        await self.receive_json(socket)
        with Encoder(self.api) as encoder:
            await self.start(socket, encoder)
            packet = encoder.encode(bytes(1920))
            for _ in range(MAX_INPUT_SAMPLES // FRAME_SAMPLES + 3):
                await socket.send(packet)
        self.assertEqual((await self.receive_json(socket))["code"], "AUDIO_TOO_LARGE")
        await socket.close()

    async def test_response_limit(self):
        class TooLarge(AudioBackend):
            async def respond(self, pcm):
                return bytes(MAX_OUTPUT_SAMPLES * 2 + 2)
        self.gateway.backend = TooLarge()
        socket = await self.open()
        await self.receive_json(socket)
        await self.upload(socket, signal(480))
        self.assertEqual((await self.receive_json(socket))["state"], "processing")
        self.assertEqual((await self.receive_json(socket))["code"], "BACKEND_AUDIO_INVALID")
        await socket.close()

    async def test_ping_is_serviced_during_backend(self):
        release = asyncio.Event()
        class Slow(AudioBackend):
            async def respond(self, pcm):
                await release.wait()
                return pcm
        self.gateway.backend = Slow()
        socket = await self.open()
        await self.receive_json(socket)
        await self.upload(socket, signal(480))
        self.assertEqual((await self.receive_json(socket))["state"], "processing")
        await socket.send('{"type":"ping"}')
        self.assertEqual((await self.receive_json(socket))["type"], "pong")
        release.set()
        self.assertEqual(len(await self.response(socket)), 960)
        await socket.close()

    async def test_backend_timeout(self):
        class Slow(AudioBackend):
            async def respond(self, pcm):
                await asyncio.sleep(10)
        self.gateway.backend = Slow()
        self.gateway.config.backend_timeout = .03
        socket = await self.open()
        await self.receive_json(socket)
        await self.upload(socket, signal(480))
        await self.receive_json(socket)
        self.assertEqual((await self.receive_json(socket))["code"], "RESPONSE_TIMEOUT")
        await socket.close()

    async def test_utterance_timeout(self):
        self.gateway.config.utterance_timeout = .03
        socket = await self.open()
        await self.receive_json(socket)
        with Encoder(self.api) as encoder:
            await self.start(socket, encoder)
        self.assertEqual((await self.receive_json(socket))["code"], "UTTERANCE_TIMEOUT")
        await socket.close()

    async def test_hello_timeout(self):
        self.gateway.config.hello_timeout = .03
        socket = await connect(self.url, ping_interval=None)
        self.assertEqual((await self.receive_json(socket))["code"], "HANDSHAKE_TIMEOUT")
        await socket.close()

    async def test_second_client_and_slot_release(self):
        first = await self.open()
        await self.receive_json(first)
        second = await connect(self.url, ping_interval=None)
        self.assertEqual((await self.receive_json(second))["code"], "SERVER_BUSY")
        await second.close()
        await first.close()
        for _ in range(100):
            if self.gateway.active is None:
                break
            await asyncio.sleep(.001)
        third = await self.open()
        self.assertEqual((await self.receive_json(third))["type"], "hello.ack")
        await third.close()

    async def test_malformed_json(self):
        socket = await self.open()
        await self.receive_json(socket)
        await socket.send("{")
        self.assertEqual((await self.receive_json(socket))["code"], "INVALID_MESSAGE")
        await socket.close()


if __name__ == "__main__":
    unittest.main()
