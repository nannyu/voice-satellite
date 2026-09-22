from __future__ import annotations

import os

import uvicorn

from .app import create_app


def main() -> None:
    host = os.getenv("STOCK_GATEWAY_HOST", "127.0.0.1")
    port = int(os.getenv("STOCK_GATEWAY_PORT", "18889"))
    uvicorn.run(create_app(), host=host, port=port, access_log=False)


if __name__ == "__main__":
    main()
