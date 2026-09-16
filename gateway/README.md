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
