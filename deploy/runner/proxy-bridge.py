#!/usr/bin/env python3
"""Expose the dependency-only Unix socket on this container's loopback."""
import socket
import socketserver
from importlib.machinery import SourceFileLoader

relay = SourceFileLoader("dependency_proxy", "/opt/hub/dependency-proxy.py").load_module().relay


class Bridge(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as upstream:
                upstream.settimeout(30)
                upstream.connect("/proxy/dependencies.sock")
                relay(self.request, upstream)
        except OSError:
            pass


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 128


with Server(("127.0.0.1", 18080), Bridge) as server:
    server.serve_forever()
