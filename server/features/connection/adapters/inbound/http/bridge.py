"""Anonymous, read-only HTTP boundary for the connection foundation."""
import json
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from features.connection.domain.snapshot import SnapshotUseCase


SNAPSHOT_PATH = "/v1/connection/snapshot"


def create_server(host: str, port: int) -> ThreadingHTTPServer:
    snapshots = SnapshotUseCase()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != SNAPSHOT_PATH:
                self.send_error(404)
                return
            snapshot = snapshots.read()
            body = json.dumps({"protocolVersion": 1, "revision": snapshot.revision}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def reject_mutation(self):
            self.send_error(405 if self.path == SNAPSHOT_PATH else 404)

        do_POST = reject_mutation
        do_PUT = reject_mutation
        do_PATCH = reject_mutation
        do_DELETE = reject_mutation

    # Resolve binding only at this infrastructure boundary; support IPv6 too.
    family = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)[0][0]

    class Server(ThreadingHTTPServer):
        address_family = family

        def get_request(self):
            client, address = super().get_request()
            client.settimeout(5)
            return client, address

    return Server((host, port), Handler)
