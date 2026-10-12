"""Stand-in for the Home Assistant webhook, to test the Phonak Bridge app.

Prints every report the phone sends. Commands given with --command are handed
out (once) in the reply to the first report that contains aid values.

    py -3.14 tools/bridge_test_server.py --port 8765
    adb reverse tcp:8765 tcp:8765
    # webhook URL in the app: http://127.0.0.1:8765/api/webhook/test

    # e.g. turn the volume of the right aid down one step, then back:
    py -3.14 tools/bridge_test_server.py --command '{"action": "volume", "sides": ["right"], "value": -1}'
"""

from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time
import uuid


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--command", action="append", default=[], help="command JSON (repeatable)")
    args = parser.parse_args()
    queue = [{"id": uuid.uuid4().hex[:12], **json.loads(c)} for c in args.command]

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            try:
                report = json.loads(body)
            except ValueError:
                self.send_error(400, "Invalid JSON")
                return
            print(time.strftime("%H:%M:%S"), json.dumps(report, indent=1), flush=True)
            commands = []
            if queue and report.get("aids"):
                commands = queue[:]
                queue.clear()
                print("-> commands", commands, flush=True)
            data = json.dumps({"commands": commands}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_args: object) -> None:
            pass

    print(f"Listening on port {args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
