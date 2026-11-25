"""HTTP contract only; no tmux/pi integration or agent-survival claim."""
import http.client
import json
import subprocess
import sys
import threading
import unittest

from features.connection.adapters.inbound.http.bridge import create_server


class ConnectionHttpTest(unittest.TestCase):
    def setUp(self):
        self.server = create_server(host="127.0.0.1", port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.stopped = False
        self.addCleanup(self.stop_server)

    def stop_server(self):
        if self.stopped:
            return
        self.stopped = True
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.assertFalse(self.thread.is_alive(), "HTTP server did not shut down")

    def request(self, method, path):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            # Deliberately no Authorization, token, pairing, or cookies.
            connection.request(method, path)
            response = connection.getresponse()
            return response.status, response.getheader("Content-Type"), response.read()
        finally:
            connection.close()

    def test_anonymous_snapshot_is_versioned_json_and_repeatably_readable(self):
        for _ in range(2):
            status, content_type, body = self.request("GET", "/v1/connection/snapshot")
            self.assertEqual(200, status)
            self.assertEqual("application/json", content_type.split(";")[0])
            snapshot = json.loads(body)
            self.assertIs(type(snapshot["protocolVersion"]), int)
            self.assertEqual(1, snapshot["protocolVersion"])
            self.assertIsInstance(snapshot["revision"], str)
            self.assertTrue(snapshot["revision"])

    def test_unknown_paths_and_mutation_requests_are_not_successful_snapshots(self):
        for method, path, expected in [
            ("GET", "/", 404),
            ("GET", "/v1/connection/snapshots", 404),
            ("GET", "/v2/connection/snapshot", 404),
            ("POST", "/v1/connection/snapshot", 405),
            ("POST", "/v1/connection/disconnect", 404),
        ]:
            with self.subTest(method=method, path=path):
                status, _, _ = self.request(method, path)
                self.assertEqual(expected, status)

    def test_closing_client_socket_and_bridge_does_not_own_external_process(self):
        # An independent process sentinel, NOT an attached pi/tmux process.
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        self.addCleanup(self.reap, process)
        client = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        client.request("GET", "/v1/connection/snapshot")
        self.assertEqual(200, client.getresponse().status)
        client.close()
        self.assertEqual(200, self.request("GET", "/v1/connection/snapshot")[0])
        self.assertIsNone(process.poll(), "client disconnect killed an independent process")
        self.stop_server()
        self.assertIsNone(process.poll(), "bridge shutdown killed an independent process")

    @staticmethod
    def reap(process):
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=3)


if __name__ == "__main__":
    unittest.main()
