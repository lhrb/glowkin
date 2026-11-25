"""Run independently of Android; Ctrl-C stops only this bridge."""
import argparse
import sys

from features.connection.adapters.inbound.http.bridge import create_server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1", help="trusted interface address (default: loopback)")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    print("WARNING: unauthenticated HTTP PC control for trusted networks only. "
          "Never expose publicly; restrict peers with firewall/Tailscale policy.", file=sys.stderr, flush=True)
    with create_server(args.bind, args.port) as server:
        print(f"Bridge listening on {server.server_address}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
