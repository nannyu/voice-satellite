# R1 Stock Gateway — P1 server slice

This directory contains the first runnable server-side slice of the stock Agent
bridge. It does **not** change an R1, DNS, routing, package state, or the current
Agent. It deliberately implements only the two P1 protocol-proving modes:

- `passthrough`: forward the single allowlisted stock route to one configured
  upstream origin while dropping credentials, cookies, and hop-by-hop headers.
- `fixed_reply`: forward first, then replace only a recognized final stock JSON
  answer. Intermediate, binary, and unknown responses pass through unchanged.

The implementation is independent. The pinned
[`SaifLau/r1-stock-bridge`](https://github.com/SaifLau/r1-stock-bridge/tree/9df3ca10a522d61f1524d0ff12ee3bb55ab20991)
code was used as a behavioral/protocol reference; no upstream source was copied.

## Safety defaults

- listens on loopback when launched by the module entry point;
- accepts loopback clients only;
- forwards only `POST /trafficRouter/cs` to one HTTP(S) origin;
- rejects origins containing credentials, paths, queries, or fragments;
- limits request/response bodies, queue time, upstream time, and concurrency;
- never forwards `Authorization`, `Cookie`, absolute URLs, or arbitrary routes;
- logs a hash tag for `ui`, byte counts, status, mode, and latency—not bodies or
  the raw device identifier;
- disables FastAPI's documentation endpoints on the device-facing service.

These controls are not proof that the stock protocol or its silent termination
behavior works on the user's R1. `fixed_reply` intentionally needs the verified
stock upstream response as a template; it does not invent a silent/cancel JSON.

## Run locally

```bash
cd services/stock-gateway
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

STOCK_GATEWAY_MODE=fixed_reply \
STOCK_GATEWAY_UPSTREAM_BASE_URL=http://127.0.0.1:18888 \
STOCK_GATEWAY_FIXED_REPLY_TEXT='这是固定回复测试。' \
.venv/bin/python -m stock_gateway
```

Default bind: `127.0.0.1:18889`. To bind a LAN address, set
`STOCK_GATEWAY_HOST` and explicitly set `STOCK_GATEWAY_ALLOWED_CLIENT_CIDRS` to
the narrow R1 subnet or address. Do not expose this entry point to the Internet.

Configuration:

| Environment variable | Default |
|---|---|
| `STOCK_GATEWAY_MODE` | `passthrough` |
| `STOCK_GATEWAY_UPSTREAM_BASE_URL` | `http://127.0.0.1:18888` |
| `STOCK_GATEWAY_FIXED_REPLY_TEXT` | `这是固定回复测试。` |
| `STOCK_GATEWAY_ALLOWED_CLIENT_CIDRS` | `127.0.0.1/32,::1/128` |
| `STOCK_GATEWAY_MAX_REQUEST_BODY_BYTES` | `1048576` |
| `STOCK_GATEWAY_MAX_RESPONSE_BODY_BYTES` | `2097152` |
| `STOCK_GATEWAY_UPSTREAM_TIMEOUT_SECONDS` | `8` |
| `STOCK_GATEWAY_QUEUE_TIMEOUT_SECONDS` | `0.25` |
| `STOCK_GATEWAY_MAX_IN_FLIGHT` | `8` |
| `STOCK_GATEWAY_HOST` / `STOCK_GATEWAY_PORT` | `127.0.0.1` / `18889` |

## Tests

```bash
cd services/stock-gateway
python3 -m pytest -q
```

The suite covers strict routing/header filtering, fixed reply recognition,
unknown-frame passthrough, a 20-turn server regression, size/client limits,
bounded upstream failure, safe configuration, and log redaction. The 20-turn
test is not acceptance case A03: A03 remains `not_run` until the real stock R1
completes 20 device turns.
