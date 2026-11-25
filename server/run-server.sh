#!/bin/sh
set -eu

server_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec cargo run --release --locked --manifest-path "$server_dir/Cargo.toml" -- "$@"
