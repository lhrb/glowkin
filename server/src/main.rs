use std::{io, num::NonZeroU16};

use clap::Parser;
use tokio::net::TcpListener;

mod features {
    pub mod connection;
}

#[derive(Parser)]
#[command(about = "Run independently of Android and Pi; Ctrl-C stops only this bridge.")]
struct Args {
    /// Trusted interface address (default: loopback).
    #[arg(long, default_value = "127.0.0.1")]
    bind: String,
    #[arg(long, default_value = "8787")]
    port: NonZeroU16,
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> io::Result<()> {
    let args = Args::parse();
    eprintln!(
        "WARNING: unauthenticated HTTP PC control for trusted networks only. \
        Never expose publicly; restrict peers with firewall/Tailscale policy."
    );
    let listener = TcpListener::bind((args.bind.as_str(), args.port.get())).await?;
    println!("Bridge listening on {}", listener.local_addr()?);
    tokio::select! {
        result = tokio::signal::ctrl_c() => result,
        result = features::connection::serve(listener) => result,
    }
}
