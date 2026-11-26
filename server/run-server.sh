#!/bin/sh
set -eu

server_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

pane_input_flag=--enable-pane-input
for arg do
    if [ "$arg" = "$pane_input_flag" ]; then
        pane_input_flag=
        break
    fi
done
if [ -n "$pane_input_flag" ]; then
    set -- "$pane_input_flag" "$@"
fi

exec cargo run --release --locked --manifest-path "$server_dir/Cargo.toml" -- "$@"
