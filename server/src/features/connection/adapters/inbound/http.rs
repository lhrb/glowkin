use std::{convert::Infallible, io, sync::Arc, time::Duration};

use http_body_util::{BodyExt, Full, Limited};
use hyper::{
    Method, Request, Response, StatusCode,
    body::{Bytes, Incoming},
    header,
    server::conn::http1,
    service::service_fn,
};
use hyper_util::rt::{TokioIo, TokioTimer};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::{net::TcpListener, time::timeout};

use super::domain::Snapshot;
use crate::features::workspace::{OpenError, Target, Tmux, Workspace};

const SNAPSHOT_PATH: &str = "/v1/connection/snapshot";
const OPEN_PATH: &str = "/v1/workspace/open-shell";
const OPEN_BODY_LIMIT: usize = 256;

pub(super) async fn serve(listener: TcpListener, snapshot: Snapshot) -> io::Result<()> {
    let snapshot = Arc::new(snapshot);
    let tmux = Arc::new(Tmux::new());
    let mut http = http1::Builder::new();
    http.keep_alive(false)
        .timer(TokioTimer::new())
        .header_read_timeout(Duration::from_secs(5));

    loop {
        let (stream, _) = listener.accept().await?;
        let snapshot = Arc::clone(&snapshot);
        let tmux = Arc::clone(&tmux);
        let service = service_fn(move |request| {
            let snapshot = Arc::clone(&snapshot);
            let tmux = Arc::clone(&tmux);
            async move { Ok::<_, Infallible>(respond(request, &snapshot, &tmux).await) }
        });
        let connection = http.serve_connection(TokioIo::new(stream), service);
        tokio::spawn(async move {
            // A disconnected or stalled client must not stop the listener.
            let _ = timeout(Duration::from_secs(10), connection).await;
        });
    }
}

async fn respond(
    request: Request<Incoming>,
    snapshot: &Snapshot,
    tmux: &Tmux,
) -> Response<Full<Bytes>> {
    let method = request.method();
    if !matches!(
        *method,
        Method::GET | Method::POST | Method::PUT | Method::PATCH | Method::DELETE
    ) {
        return empty(StatusCode::NOT_IMPLEMENTED);
    }

    // Match the path and query together so query strings are rejected too.
    let allowed = match request.uri().path_and_query().map(|target| target.as_str()) {
        Some(SNAPSHOT_PATH) => Method::GET,
        Some(OPEN_PATH) => Method::POST,
        _ => return empty(StatusCode::NOT_FOUND),
    };
    if *method != allowed {
        let mut response = empty(StatusCode::METHOD_NOT_ALLOWED);
        response.headers_mut().insert(
            header::ALLOW,
            header::HeaderValue::from_str(allowed.as_str()).unwrap(),
        );
        return response;
    }

    if allowed == Method::POST {
        return empty(open_shell(request, tmux).await);
    }

    let mut body = json!({
        "protocolVersion": 1,
        "revision": snapshot.revision,
        "workspace": workspace_json(tmux.inventory().await),
    });
    let mut encoded = body.to_string();
    if encoded.len() > 65536 {
        body["workspace"] = workspace_json(Workspace::Unavailable);
        encoded = body.to_string();
    }
    let mut response = Response::new(Full::new(Bytes::from(encoded)));
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static("application/json"),
    );
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-store"),
    );
    response
}

fn workspace_json(workspace: Workspace) -> Value {
    match workspace {
        Workspace::Ready(session) => {
            let windows: Vec<_> = session
                .windows
                .into_iter()
                .map(|window| json!({"id": window.id, "name": window.name}))
                .collect();
            let Target {
                session_id,
                server_pid,
                server_start,
            } = session.target;
            json!({"status": "ready", "session": {
                "id": format!("${session_id}"), "name": session.name,
                "generation": format!("{server_pid}-{server_start}"), "windows": windows,
            }})
        }
        Workspace::NoSession => json!({"status": "no_session"}),
        Workspace::MultipleSessions => json!({"status": "multiple_sessions"}),
        Workspace::Unavailable => json!({"status": "unavailable"}),
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct OpenRequest {
    session_id: String,
    generation: String,
}

impl OpenRequest {
    fn target(self) -> Option<Target> {
        fn decimal<T: std::str::FromStr>(value: &str) -> Option<T> {
            if !value.bytes().all(|byte| byte.is_ascii_digit())
                || (value.len() > 1 && value.starts_with('0'))
            {
                return None;
            }
            value.parse().ok()
        }
        let (pid, start) = self.generation.split_once('-')?;
        Some(Target {
            session_id: decimal(self.session_id.strip_prefix('$')?)?,
            server_pid: decimal(pid)?,
            server_start: decimal(start)?,
        })
    }
}

async fn open_shell(request: Request<Incoming>, tmux: &Tmux) -> StatusCode {
    let headers = request.headers();
    if headers.contains_key(header::ORIGIN)
        || headers.contains_key(header::REFERER)
        || headers
            .keys()
            .any(|name| name.as_str().starts_with("sec-fetch-"))
    {
        return StatusCode::FORBIDDEN;
    }
    let mut content_types = headers.get_all(header::CONTENT_TYPE).iter();
    if content_types
        .next()
        .is_none_or(|value| !value.as_bytes().eq_ignore_ascii_case(b"application/json"))
        || content_types.next().is_some()
    {
        return StatusCode::UNSUPPORTED_MEDIA_TYPE;
    }
    let mut lengths = headers.get_all(header::CONTENT_LENGTH).iter();
    let Some(length) = lengths
        .next()
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<usize>().ok())
    else {
        return StatusCode::BAD_REQUEST;
    };
    if headers.contains_key(header::TRANSFER_ENCODING) || lengths.next().is_some() || length == 0 {
        return StatusCode::BAD_REQUEST;
    }
    if length > OPEN_BODY_LIMIT {
        return StatusCode::PAYLOAD_TOO_LARGE;
    }
    let body = match timeout(
        Duration::from_secs(2),
        Limited::new(request.into_body(), OPEN_BODY_LIMIT).collect(),
    )
    .await
    {
        Ok(Ok(body)) => body.to_bytes(),
        Ok(Err(_)) => return StatusCode::BAD_REQUEST,
        Err(_) => return StatusCode::REQUEST_TIMEOUT,
    };
    if body.len() != length {
        return StatusCode::BAD_REQUEST;
    }
    let Some(target) = serde_json::from_slice::<OpenRequest>(&body)
        .ok()
        .and_then(OpenRequest::target)
    else {
        return StatusCode::BAD_REQUEST;
    };
    match tmux.open_shell(target).await {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(OpenError::Unavailable) => StatusCode::CONFLICT,
        Err(OpenError::Uncertain) => StatusCode::SERVICE_UNAVAILABLE,
    }
}

fn empty(status: StatusCode) -> Response<Full<Bytes>> {
    let mut response = Response::new(Full::default());
    *response.status_mut() = status;
    response
}
