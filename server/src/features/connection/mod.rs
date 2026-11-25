use std::io;

use tokio::net::TcpListener;
use uuid::Uuid;

mod domain;
#[path = "adapters/inbound/http.rs"]
mod http;

pub(crate) async fn serve(listener: TcpListener) -> io::Result<()> {
    let snapshot = domain::Snapshot {
        revision: Uuid::new_v4().simple().to_string(),
    };
    http::serve(listener, snapshot).await
}
