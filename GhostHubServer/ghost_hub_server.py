"""Ghost Hub server: shares the newest Ghost app APKs with your phone over home Wi-Fi.

HTTP on port 8765 serves the "www" folder (index.json + apps/*.apk).
UDP on port 8766 answers "GHOSTHUB_DISCOVER" so the phone can find this PC even if its address changes.
Nothing is sent to the internet; only devices on your home network can reach it.
"""
import http.server
import socket
import socketserver
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
WWW = HERE / "www"
HTTP_PORT = 8765
UDP_PORT = 8766
LOG = HERE / "server.log"


def log(msg: str) -> None:
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    try:
        with LOG.open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".apk": "application/vnd.android.package-archive",
        ".json": "application/json",
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WWW), **kwargs)

    def end_headers(self):
        if self.path.endswith(".json"):
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def list_directory(self, path):
        # Don't show folder listings; the phone only needs index.json and the APKs.
        self.send_error(404)
        return None

    def log_message(self, fmt, *args):
        log(f"{self.client_address[0]} {fmt % args}")


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def discovery() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", UDP_PORT))
    name = socket.gethostname()
    while True:
        try:
            data, addr = sock.recvfrom(256)
            if data.strip() == b"GHOSTHUB_DISCOVER":
                sock.sendto(f"GHOSTHUB {HTTP_PORT} {name}".encode(), addr)
                log(f"discovered by {addr[0]}")
        except OSError as e:
            log(f"discovery error: {e}")
            time.sleep(1)


def main() -> None:
    WWW.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=discovery, daemon=True).start()
    httpd = Server(("0.0.0.0", HTTP_PORT), Handler)
    log(f"Ghost Hub server started on port {HTTP_PORT}")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
