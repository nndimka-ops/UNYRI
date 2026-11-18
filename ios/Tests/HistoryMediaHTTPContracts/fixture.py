"""Loopback-only disposable HTTP fixture; no application server or user data."""
import http.server
import socketserver
import sys


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, code, data=b"", **headers):
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        for name, value in headers.items():
            self.send_header(name.replace("_", "-"), value)
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_PUT(self):
        if self.headers.get("Authorization"):
            return self.respond(403)
        if self.headers.get("Content-Length") != "65536":
            return self.respond(411)
        body = self.rfile.read(65536)
        if body != bytes(range(256)) * 256:
            return self.respond(422)
        if self.path == "/put":
            return self.respond(200)
        if self.path == "/put-overflow":
            return self.respond(200, b"x" * 65537)
        if self.path == "/put-redirect":
            return self.respond(307, Location="/put")
        return self.respond(404)

    def do_GET(self):
        if self.headers.get("Authorization"):
            return self.respond(403)
        if self.path == "/get":
            return self.respond(200, b"abc")
        if self.path == "/get-overflow":
            return self.respond(200, b"abcd")
        if self.path == "/get-redirect":
            return self.respond(302, Location="/get")
        return self.respond(404)


class LoopbackServer(http.server.ThreadingHTTPServer):
    def server_bind(self):
        # HTTPServer normally reverse-resolves its hostname. That is irrelevant
        # for loopback tests and can stall on hosted runners' DNS configuration.
        socketserver.TCPServer.server_bind(self)
        self.server_name = "localhost"
        self.server_port = self.server_address[1]


if __name__ == "__main__":
    LoopbackServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
