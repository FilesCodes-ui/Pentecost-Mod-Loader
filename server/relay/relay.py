import hmac
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = os.environ.get("RELAY_KEY", "")
MOJANG = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
NAME = re.compile(r"^[A-Za-z0-9_]{1,16}$")
SERVER_ID = re.compile(r"^[0-9a-f]{40}$")


class Relay(BaseHTTPRequestHandler):
    def reply(self, status, body=b"", kind="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        given = self.headers.get("X-Relay-Key", "")
        if not KEY or not hmac.compare_digest(given, KEY):
            return self.reply(403)
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        name = q.get("username", [""])[0]
        sid = q.get("serverId", [""])[0]
        if not NAME.match(name) or not SERVER_ID.match(sid):
            return self.reply(400)
        url = MOJANG + "?" + urllib.parse.urlencode({"username": name, "serverId": sid})
        req = urllib.request.Request(url, headers={"User-Agent": "Pentecost-Relay/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return self.reply(r.status, r.read(65536))
        except urllib.error.HTTPError as e:
            return self.reply(e.code)
        except Exception:
            return self.reply(502)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8766), Relay).serve_forever()
