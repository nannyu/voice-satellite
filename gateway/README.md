# Voice Gateway

Optional bridge between legacy/device clients and fast-moving server integrations.

Primary responsibilities:

- authenticate devices
- terminate the versioned Voice Satellite protocol
- map voice sessions to Home Assistant Assist or a custom Agent
- normalize TTS/response streaming
- route media/announcement commands
- expose diagnostics and latency metrics

The gateway is an architectural boundary, not a mandatory deployment component until the Home Assistant integration spike decides direct vs bridge mode.

## Protocol test server

`test_server.py` is the reference implementation of the docs/04 draft protocol
and doubles as the echo server for Android end-to-end session validation.
Python 3.8+, dependencies pinned in `requirements.txt`.

```bash
python3 -m venv /tmp/voice-satellite-gateway
/tmp/voice-satellite-gateway/bin/pip install -r gateway/requirements.txt
/tmp/voice-satellite-gateway/bin/python gateway/test_server.py --host 0.0.0.0 --port 8765 --mode echo
```

Response modes (`--mode`):

- `echo` (default): returns the captured PCM verbatim — the most useful
  end-to-end loop test.
- `tone`: answers every voice session with a generated 440 Hz / 1 s 16 kHz
  PCM16 sine (mirrors `PcmAudio.sineTone`) to validate the downlink only.
- `silence`: control messages only, no response audio.

Fault injection for reconnect testing:

- `--drop-after N`: close the connection N seconds after hello.ack.
- `--no-ack`: accept hello but never send hello.ack.
- `--slow-response N`: wait N seconds between voice.end and state.processing.

Other knobs: `--heartbeat-seconds` (advertised in hello.ack, default 30),
`--heartbeat-timeout-seconds` (server dead-link margin, default 10; the server
closes after heartbeat_seconds + timeout of total silence, mirroring the
Android `HeartbeatMonitor`).

Concurrency: one active client. A second connection gets a `SERVER_BUSY`
error and close code 1013; the first client is unaffected.

Logs are structured `key=value` lines. Each voice session ends with one
`SESSION` summary (`trigger frames bytes reason duration_ms mode
response_bytes`), which is the statistic source for the 20-consecutive-session
Phase 2 acceptance.

Tests (also run by `.github/workflows/gateway-test.yml`):

```bash
/tmp/voice-satellite-gateway/bin/python -m pytest gateway/test_protocol.py -v
```

Android joint debugging: once the app wires `ConnectionSupervisor` to a
configurable endpoint, point it at `ws://<this-computer-LAN-IP>:8765` and run
the server with `--host 0.0.0.0`. The diagnostics APK does not use the gateway
yet; the automatic R1 Audio Probe is independent of it.
