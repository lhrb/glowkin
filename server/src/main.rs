use std::{io, num::NonZeroU16};

use clap::Parser;
use tokio::net::TcpListener;

mod features {
    pub mod connection;
    pub mod workspace;
}

#[derive(Parser)]
#[command(about = "Run independently of Android and Pi; Ctrl-C stops only this bridge.")]
struct Args {
    /// Trusted interface address (default: loopback).
    #[arg(long, default_value = "127.0.0.1")]
    bind: String,
    #[arg(long, default_value = "8787")]
    port: NonZeroU16,
    /// Allow reachable peers to type into eligible panes.
    #[arg(long)]
    enable_pane_input: bool,
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> io::Result<()> {
    let args = Args::parse();
    eprintln!(
        "WARNING: unauthenticated HTTP PC control for trusted networks only. \
        Any reachable peer can read eligible pane contents. \
        Never expose publicly; restrict peers with firewall/Tailscale policy."
    );
    if args.enable_pane_input {
        eprintln!(
            "WARNING: pane input is enabled. Any reachable peer can type into eligible panes \
            and potentially execute commands as the pane's user."
        );
    }
    let listener = TcpListener::bind((args.bind.as_str(), args.port.get())).await?;
    println!("Bridge listening on {}", listener.local_addr()?);
    tokio::select! {
        result = tokio::signal::ctrl_c() => result,
        result = features::connection::serve(listener, args.enable_pane_input) => result,
    }
}
