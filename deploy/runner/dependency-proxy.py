#!/usr/bin/env python3
"""HTTPS CONNECT over a Unix socket. Only public IPs of explicit dependency hosts.

The build has no network interfaces except loopback. The socket is its only
egress path; no GitHub tokens, credentials or application environment live here.
"""
import ipaddress
import os
import select
import socket
import socketserver
import threading

ALLOWED = frozenset({
    "repo.maven.apache.org", "repo1.maven.org", "plugins.gradle.org",
    "plugins-artifacts.gradle.org", "services.gradle.org", "downloads.gradle.org",
    "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
    "registry.npmjs.org", "nodejs.org",
    "dl.google.com", "maven.google.com", "download.jetbrains.com", "download-cdn.jetbrains.com",
    "cache-redirector.jetbrains.com", "maven.pkg.jetbrains.space",
})
SLOTS = threading.BoundedSemaphore(128)


def destination(authority):
    host, separator, port = authority.rpartition(":")
    if not separator or port != "443" or host.lower() not in ALLOWED:
        raise ValueError("Destination is not allowed")
    addresses = socket.getaddrinfo(host.lower(), 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
        raise ValueError("Non-public destination")
    return addresses


def relay(first, second):
    while True:
        ready, _, _ = select.select([first, second], [], [], 60)
        if not ready:
            return
        for source in ready:
            data = source.recv(65536)
            if not data:
                return
            (second if source is first else first).sendall(data)


class Proxy(socketserver.BaseRequestHandler):
    def handle(self):
        if not SLOTS.acquire(blocking=False):
            self.request.sendall(b"HTTP/1.1 503 Busy\r\n\r\n")
            return
        try:
            self.request.settimeout(30)
            header = bytearray()
            while not header.endswith(b"\r\n\r\n"):
                if len(header) >= 8192:
                    raise ValueError("Headers too large")
                part = self.request.recv(1)
                if not part:
                    return
                header.extend(part)
            method, authority, version = header.split(b"\r\n", 1)[0].decode("ascii").split(" ")
            if method != "CONNECT" or version != "HTTP/1.1":
                raise ValueError("Only HTTPS CONNECT is supported")
            addresses = destination(authority)
            family, kind, protocol, _, address = addresses[0]
            # Connect to the validated IP, avoiding a second DNS lookup/rebinding.
            with socket.socket(family, kind, protocol) as upstream:
                upstream.settimeout(30)
                upstream.connect(address)
                self.request.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                relay(self.request, upstream)
        except (OSError, ValueError, UnicodeError):
            try:
                self.request.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\n")
            except OSError:
                pass
        finally:
            SLOTS.release()


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True
    request_queue_size = 128


if __name__ == "__main__":
    path = "/proxy/dependencies.sock"
    if os.path.exists(path):
        os.unlink(path)
    with Server(path, Proxy) as server:
        os.chmod(path, 0o666)
        server.serve_forever()
