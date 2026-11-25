use std::{convert::Infallible, io, time::Duration};

use http_body_util::Full;
use hyper::{
    Method, Request, Response, StatusCode, body::Bytes, header, server::conn::http1,
    service::service_fn,
};
use hyper_util::rt::{TokioIo, TokioTimer};
use tokio::net::TcpListener;

use super::domain::Snapshot;

const SNAPSHOT_PATH: &str = "/v1/connection/snapshot";

pub(super) async fn serve(listener: TcpListener, snapshot: Snapshot) -> io::Result<()> {
    let body = Bytes::from(
        serde_json::json!({ "protocolVersion": 1, "revision": snapshot.revision }).to_string(),
    );

    let mut http = http1::Builder::new();
    http.keep_alive(false)
        .timer(TokioTimer::new())
        .header_read_timeout(Duration::from_secs(5));

    loop {
        let (stream, _) = listener.accept().await?;
        let body = body.clone();
        let service = service_fn(move |request| {
            let response = snapshot_response(request, &body);
            async { Ok::<_, Infallible>(response) }
        });
        let connection = http.serve_connection(TokioIo::new(stream), service);
        tokio::spawn(async move {
            // A disconnected or malformed client must not stop the listener.
            let _ = connection.await;
        });
    }
}

fn snapshot_response(
    request: Request<hyper::body::Incoming>,
    body: &Bytes,
) -> Response<Full<Bytes>> {
    let status = match *request.method() {
        Method::GET | Method::POST | Method::PUT | Method::PATCH | Method::DELETE => {
            if request.uri().path_and_query().map(|path| path.as_str()) != Some(SNAPSHOT_PATH) {
                StatusCode::NOT_FOUND
            } else if request.method() == Method::GET {
                StatusCode::OK
            } else {
                StatusCode::METHOD_NOT_ALLOWED
            }
        }
        _ => StatusCode::NOT_IMPLEMENTED,
    };

    let mut response = Response::new(Full::default());
    *response.status_mut() = status;
    if status == StatusCode::OK {
        *response.body_mut() = Full::new(body.clone());
        response.headers_mut().insert(
            header::CONTENT_TYPE,
            header::HeaderValue::from_static("application/json"),
        );
        response.headers_mut().insert(
            header::CACHE_CONTROL,
            header::HeaderValue::from_static("no-store"),
        );
    } else if status == StatusCode::METHOD_NOT_ALLOWED {
        response
            .headers_mut()
            .insert(header::ALLOW, header::HeaderValue::from_static("GET"));
    }
    response
}
