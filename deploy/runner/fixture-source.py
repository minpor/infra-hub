"""Test-only source endpoint, used exclusively in the disposable smoke stack."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


class Source(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("X-Worker-Secret") != "isolated-repository-test-secret-32":
            self.send_error(401)
            return
        content = Path("/fixture/source.tar.gz").read_bytes()
        self.send_response(200)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *_):
        pass


HTTPServer(("0.0.0.0", 8080), Source).serve_forever()
