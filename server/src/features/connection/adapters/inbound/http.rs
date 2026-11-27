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
use crate::features::workspace::{
    CHAT_REQUEST_LIMIT, ChatSnapshot, DraftText, InputError, LiteralText, OpenError, PaneInput,
    PaneInputCommand, PaneKey, PaneTarget, PiChatError, PiInstanceId, ReadError, Target, Tmux,
    Workspace, edit_pi_draft, read_pi_chat,
};

const SNAPSHOT_PATH: &str = "/v1/connection/snapshot";
const OPEN_PATH: &str = "/v1/workspace/open-shell";
const READ_PATH: &str = "/v1/workspace/read-pane";
const SEND_INPUT_PATH: &str = "/v1/workspace/send-input";
const READ_PI_CHAT_PATH: &str = "/v1/workspace/read-pi-chat";
const EDIT_PI_DRAFT_PATH: &str = "/v1/workspace/edit-pi-draft";
const SNAPSHOT_BODY_LIMIT: usize = 65536;
const TARGET_BODY_LIMIT: usize = 256;
const INPUT_BODY_LIMIT: usize = 2048;

pub(super) async fn serve(
    listener: TcpListener,
    snapshot: Snapshot,
    enable_pane_input: bool,
) -> io::Result<()> {
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
            async move {
                Ok::<_, Infallible>(respond(request, &snapshot, &tmux, enable_pane_input).await)
            }
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
    enable_pane_input: bool,
) -> Response<Full<Bytes>> {
    let is_chat = matches!(request.uri().path(), READ_PI_CHAT_PATH | EDIT_PI_DRAFT_PATH);
    let mut response = dispatch(request, snapshot, tmux, enable_pane_input).await;
    if is_chat {
        response.headers_mut().insert(
            header::CACHE_CONTROL,
            header::HeaderValue::from_static("no-store"),
        );
    }
    response
}

async fn dispatch(
    request: Request<Incoming>,
    snapshot: &Snapshot,
    tmux: &Tmux,
    enable_pane_input: bool,
) -> Response<Full<Bytes>> {
    let method = request.method();
    if !matches!(
        *method,
        Method::GET | Method::POST | Method::PUT | Method::PATCH | Method::DELETE
    ) {
        return empty(StatusCode::NOT_IMPLEMENTED);
    }

    // Match the path and query together so query strings are rejected too.
    let path = request.uri().path_and_query().map(|target| target.as_str());
    let allowed = match path {
        Some(SNAPSHOT_PATH) => Method::GET,
        Some(OPEN_PATH | READ_PATH | SEND_INPUT_PATH | READ_PI_CHAT_PATH | EDIT_PI_DRAFT_PATH) => {
            Method::POST
        }
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

    match path {
        Some(OPEN_PATH) => return empty(open_shell(request, tmux).await),
        Some(READ_PATH) => return pane_response(read_pane(request, tmux).await),
        Some(READ_PI_CHAT_PATH) => {
            return chat_response(read_chat(request, tmux).await, enable_pane_input);
        }
        Some(EDIT_PI_DRAFT_PATH) => {
            if !enable_pane_input {
                return empty(StatusCode::FORBIDDEN);
            }
            let status = edit_draft(request, tmux, &snapshot.revision)
                .await
                .map_or_else(|status| status, |()| StatusCode::NO_CONTENT);
            return empty(status);
        }
        Some(SEND_INPUT_PATH) => {
            if !enable_pane_input {
                return empty(StatusCode::FORBIDDEN);
            }
            return empty(send_input(request, tmux).await);
        }
        _ => (),
    }

    let workspace = tmux.inventory().await;
    let mut body = json!({
        "protocolVersion": 1,
        "revision": snapshot.revision,
        "workspace": workspace_json(&workspace, true),
    });
    let mut encoded = body.to_string();
    if encoded.len() > SNAPSHOT_BODY_LIMIT {
        body["workspace"] = workspace_json(&workspace, false);
        encoded = body.to_string();
        if encoded.len() > SNAPSHOT_BODY_LIMIT {
            body["workspace"] = workspace_json(&Workspace::Unavailable, false);
            encoded = body.to_string();
        }
    }
    // Fit the advertisement only after choosing the same inventory older clients received.
    body["paneInputEnabled"] = json!(enable_pane_input);
    let advertised = body.to_string();
    if advertised.len() <= SNAPSHOT_BODY_LIMIT {
        encoded = advertised;
    } else {
        body.as_object_mut().unwrap().remove("paneInputEnabled");
    }
    // Folder data may use only the budget left after the baseline response.
    if let Workspace::Ready(session) = &workspace
        && session.windows.iter().any(|window| window.folder.is_some())
        && let Some(entries) = body
            .pointer_mut("/workspace/session/windows")
            .and_then(Value::as_array_mut)
    {
        for (entry, window) in entries.iter_mut().zip(&session.windows) {
            if let Some(folder) = &window.folder {
                entry["folder"] = json!({"key": folder.key, "name": folder.name});
            }
        }
        let with_folders = body.to_string();
        if with_folders.len() <= SNAPSHOT_BODY_LIMIT {
            encoded = with_folders;
        }
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

fn workspace_json(workspace: &Workspace, with_panes: bool) -> Value {
    match workspace {
        Workspace::Ready(session) => {
            let windows: Vec<_> = session
                .windows
                .iter()
                .map(|window| {
                    let mut entry = json!({"id": window.id, "name": window.name});
                    if with_panes && let Some(pane_id) = &window.pane_id {
                        entry["paneId"] = json!(pane_id);
                    }
                    entry
                })
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PaneRequest {
    session_id: String,
    generation: String,
    window_id: String,
    pane_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct SendInputRequest {
    session_id: String,
    generation: String,
    window_id: String,
    pane_id: String,
    input: InputRequest,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReadChatRequest {
    session_id: String,
    generation: String,
    window_id: String,
    pane_id: String,
    #[serde(default, deserialize_with = "present_string")]
    instance_id: Option<String>,
}

fn present_string<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<String>, D::Error> {
    String::deserialize(deserializer).map(Some)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct EditDraftRequest {
    session_id: String,
    generation: String,
    window_id: String,
    pane_id: String,
    instance_id: String,
    draft_revision: String,
    text: String,
    bridge_revision: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
enum InputRequest {
    Text(String),
    Key(String),
}

fn decimal<T: std::str::FromStr>(value: &str) -> Option<T> {
    if !value.bytes().all(|byte| byte.is_ascii_digit())
        || (value.len() > 1 && value.starts_with('0'))
    {
        return None;
    }
    value.parse().ok()
}

fn session_target(id: &str, generation: &str) -> Option<Target> {
    let (pid, start) = generation.split_once('-')?;
    Some(Target {
        session_id: decimal(id.strip_prefix('$')?)?,
        server_pid: decimal(pid)?,
        server_start: decimal(start)?,
    })
}

fn pane_target(
    session_id: &str,
    generation: &str,
    window_id: &str,
    pane_id: &str,
) -> Option<PaneTarget> {
    Some(PaneTarget {
        session: session_target(session_id, generation)?,
        window_id: decimal(window_id.strip_prefix('@')?)?,
        pane_id: decimal(pane_id.strip_prefix('%')?)?,
    })
}

async fn read_target<T: serde::de::DeserializeOwned>(
    request: Request<Incoming>,
) -> Result<T, StatusCode> {
    let body = read_json_body(request, TARGET_BODY_LIMIT).await?;
    serde_json::from_slice(&body).map_err(|_| StatusCode::BAD_REQUEST)
}

async fn read_json_body(
    request: Request<Incoming>,
    body_limit: usize,
) -> Result<Bytes, StatusCode> {
    let headers = request.headers();
    if headers.contains_key(header::ORIGIN)
        || headers.contains_key(header::REFERER)
        || headers
            .keys()
            .any(|name| name.as_str().starts_with("sec-fetch-"))
    {
        return Err(StatusCode::FORBIDDEN);
    }
    let mut content_types = headers.get_all(header::CONTENT_TYPE).iter();
    if content_types
        .next()
        .is_none_or(|value| !value.as_bytes().eq_ignore_ascii_case(b"application/json"))
        || content_types.next().is_some()
    {
        return Err(StatusCode::UNSUPPORTED_MEDIA_TYPE);
    }
    let mut lengths = headers.get_all(header::CONTENT_LENGTH).iter();
    let Some(length) = lengths
        .next()
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<usize>().ok())
    else {
        return Err(StatusCode::BAD_REQUEST);
    };
    if headers.contains_key(header::TRANSFER_ENCODING) || lengths.next().is_some() || length == 0 {
        return Err(StatusCode::BAD_REQUEST);
    }
    if length > body_limit {
        return Err(StatusCode::PAYLOAD_TOO_LARGE);
    }
    let body = match timeout(
        Duration::from_secs(2),
        Limited::new(request.into_body(), body_limit).collect(),
    )
    .await
    {
        Ok(Ok(body)) => body.to_bytes(),
        Ok(Err(_)) => return Err(StatusCode::BAD_REQUEST),
        Err(_) => return Err(StatusCode::REQUEST_TIMEOUT),
    };
    if body.len() != length {
        return Err(StatusCode::BAD_REQUEST);
    }
    Ok(body)
}

async fn open_shell(request: Request<Incoming>, tmux: &Tmux) -> StatusCode {
    let request: OpenRequest = match read_target(request).await {
        Ok(request) => request,
        Err(status) => return status,
    };
    let Some(target) = session_target(&request.session_id, &request.generation) else {
        return StatusCode::BAD_REQUEST;
    };
    match tmux.open_shell(target).await {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(OpenError::Unavailable) => StatusCode::CONFLICT,
        Err(OpenError::Uncertain) => StatusCode::SERVICE_UNAVAILABLE,
    }
}

async fn read_pane(request: Request<Incoming>, tmux: &Tmux) -> Result<String, StatusCode> {
    let request: PaneRequest = read_target(request).await?;
    let target = pane_target(
        &request.session_id,
        &request.generation,
        &request.window_id,
        &request.pane_id,
    )
    .ok_or(StatusCode::BAD_REQUEST)?;
    tmux.read_pane(target).await.map_err(|error| match error {
        ReadError::TargetUnavailable => StatusCode::CONFLICT,
        ReadError::CaptureFailed => StatusCode::SERVICE_UNAVAILABLE,
    })
}

async fn send_input(request: Request<Incoming>, tmux: &Tmux) -> StatusCode {
    let body = match read_json_body(request, INPUT_BODY_LIMIT).await {
        Ok(body) => body,
        Err(status) => return status,
    };
    let Some(command) = input_command(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    match tmux.send_input(command).await {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(InputError::Rejected) => StatusCode::CONFLICT,
        Err(InputError::Uncertain) => StatusCode::SERVICE_UNAVAILABLE,
    }
}

async fn read_chat(request: Request<Incoming>, tmux: &Tmux) -> Result<ChatSnapshot, StatusCode> {
    let body = read_json_body(request, TARGET_BODY_LIMIT).await?;
    let request: ReadChatRequest = parse_object(&body).ok_or(StatusCode::BAD_REQUEST)?;
    let target = pane_target(
        &request.session_id,
        &request.generation,
        &request.window_id,
        &request.pane_id,
    )
    .ok_or(StatusCode::BAD_REQUEST)?;
    let expected = match &request.instance_id {
        Some(id) => Some(PiInstanceId::new(id).ok_or(StatusCode::BAD_REQUEST)?),
        None => None,
    };
    read_pi_chat(tmux, target, expected)
        .await
        .map_err(chat_error_status)
}

async fn edit_draft(
    request: Request<Incoming>,
    tmux: &Tmux,
    bridge_revision: &str,
) -> Result<(), StatusCode> {
    let body = read_json_body(request, CHAT_REQUEST_LIMIT).await?;
    let request: EditDraftRequest = parse_object(&body).ok_or(StatusCode::BAD_REQUEST)?;
    let target = pane_target(
        &request.session_id,
        &request.generation,
        &request.window_id,
        &request.pane_id,
    )
    .ok_or(StatusCode::BAD_REQUEST)?;
    let expected = PiInstanceId::new(&request.instance_id).ok_or(StatusCode::BAD_REQUEST)?;
    let text = DraftText::new(&request.text).ok_or(StatusCode::BAD_REQUEST)?;
    if request.bridge_revision != bridge_revision {
        return Err(StatusCode::CONFLICT);
    }
    edit_pi_draft(tmux, target, expected, &request.draft_revision, text)
        .await
        .map_err(chat_error_status)
}

fn chat_error_status(error: PiChatError) -> StatusCode {
    match error {
        PiChatError::Unsupported => StatusCode::NOT_FOUND,
        PiChatError::Exited => StatusCode::GONE,
        PiChatError::Conflict => StatusCode::CONFLICT,
        PiChatError::Uncertain => StatusCode::SERVICE_UNAVAILABLE,
    }
}

fn chat_response(
    result: Result<ChatSnapshot, StatusCode>,
    input_enabled: bool,
) -> Response<Full<Bytes>> {
    let snapshot = match result {
        Ok(snapshot) => snapshot,
        Err(status) => return empty(status),
    };
    #[derive(serde::Serialize)]
    #[serde(rename_all = "camelCase")]
    struct ChatResponse {
        #[serde(flatten)]
        snapshot: ChatSnapshot,
        input_enabled: bool,
    }
    let body = serde_json::to_vec(&ChatResponse {
        snapshot,
        input_enabled,
    })
    .unwrap();
    let mut response = Response::new(Full::new(Bytes::from(body)));
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static("application/json"),
    );
    response.headers_mut().insert(
        header::X_CONTENT_TYPE_OPTIONS,
        header::HeaderValue::from_static("nosniff"),
    );
    response
}

fn parse_object<T: serde::de::DeserializeOwned>(body: &[u8]) -> Option<T> {
    if body.iter().find(|byte| !byte.is_ascii_whitespace()) != Some(&b'{') {
        return None;
    }
    serde_json::from_slice(body).ok()
}

fn input_command(body: &[u8]) -> Option<PaneInputCommand> {
    let request: SendInputRequest = parse_object(body)?;
    let target = pane_target(
        &request.session_id,
        &request.generation,
        &request.window_id,
        &request.pane_id,
    )?;
    let input = match request.input {
        InputRequest::Text(text) => PaneInput::Text(LiteralText::new(text)?),
        InputRequest::Key(key) => PaneInput::Key(match key.as_str() {
            "enter" => PaneKey::Enter,
            "escape" => PaneKey::Escape,
            "up" => PaneKey::Up,
            "down" => PaneKey::Down,
            "left" => PaneKey::Left,
            "right" => PaneKey::Right,
            "backspace" => PaneKey::Backspace,
            "ctrl-o" => PaneKey::CtrlO,
            _ => return None,
        }),
    };
    Some(PaneInputCommand { target, input })
}

fn pane_response(result: Result<String, StatusCode>) -> Response<Full<Bytes>> {
    let mut response = match result {
        Ok(text) => {
            let mut response = Response::new(Full::new(Bytes::from(text)));
            response.headers_mut().insert(
                header::CONTENT_TYPE,
                header::HeaderValue::from_static("text/plain; charset=utf-8"),
            );
            response.headers_mut().insert(
                header::X_CONTENT_TYPE_OPTIONS,
                header::HeaderValue::from_static("nosniff"),
            );
            response
        }
        Err(status) => empty(status),
    };
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-store"),
    );
    response
}

fn empty(status: StatusCode) -> Response<Full<Bytes>> {
    let mut response = Response::new(Full::default());
    *response.status_mut() = status;
    response
}

#[cfg(test)]
mod tests {
    use super::*;

    const IDENTITY: &str = r#""sessionId":"$1","generation":"2-3","windowId":"@4","paneId":"%5""#;

    fn parse_input(input: &str) -> Option<PaneInputCommand> {
        input_command(format!(r#"{{{IDENTITY},"input":{input}}}"#).as_bytes())
    }

    #[tokio::test]
    async fn chat_read_response_adds_input_flag_without_a_local_status() {
        let body = json!({
            "instanceId": "0123456789abcdef0123456789abcdef", "draftRevision": "1", "draft": "π",
            "messages": [], "subagents": null, "activity": "idle", "queued": false,
        });
        let snapshot = serde_json::from_value(body.clone()).unwrap();
        let response = chat_response(Ok(snapshot), false);
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let actual: Value = serde_json::from_slice(&bytes).unwrap();
        let mut expected = body;
        expected["inputEnabled"] = json!(false);
        assert_eq!(actual, expected);
    }

    #[test]
    fn maps_identity_and_preserves_literal_text() {
        let command = parse_input(r#"{"text":" π -- ; Enter "}"#).unwrap();
        assert_eq!(command.target.session.session_id, 1);
        assert_eq!(command.target.session.server_pid, 2);
        assert_eq!(command.target.session.server_start, 3);
        assert_eq!(command.target.window_id, 4);
        assert_eq!(command.target.pane_id, 5);
        let PaneInput::Text(text) = command.input else {
            panic!("expected literal text");
        };
        assert_eq!(text.as_str(), " π -- ; Enter ");
    }

    #[test]
    fn maps_exact_key_names() {
        for name in [
            "enter",
            "escape",
            "up",
            "down",
            "left",
            "right",
            "backspace",
            "ctrl-o",
        ] {
            let command = parse_input(&format!(r#"{{"key":"{name}"}}"#)).unwrap();
            assert!(matches!(
                (name, command.input),
                ("enter", PaneInput::Key(PaneKey::Enter))
                    | ("escape", PaneInput::Key(PaneKey::Escape))
                    | ("up", PaneInput::Key(PaneKey::Up))
                    | ("down", PaneInput::Key(PaneKey::Down))
                    | ("left", PaneInput::Key(PaneKey::Left))
                    | ("right", PaneInput::Key(PaneKey::Right))
                    | ("backspace", PaneInput::Key(PaneKey::Backspace))
                    | ("ctrl-o", PaneInput::Key(PaneKey::CtrlO))
            ));
        }
    }

    #[test]
    fn rejects_invalid_input_payloads() {
        for input in [
            r#"{}"#,
            r#"null"#,
            r#""enter""#,
            r#"{"text":null}"#,
            r#"{"text":true}"#,
            r#"{"text":""}"#,
            r#"{"text":"line\nend"}"#,
            r#"{"text":"\u001b[A"}"#,
            r#"{"key":null}"#,
            r#"{"key":{"enter":null}}"#,
            r#"{"key":"Enter"}"#,
            r#"{"key":"ctrl+o"}"#,
            r#"{"Key":"enter"}"#,
            r#"{"key":"enter","text":"x"}"#,
            r#"{"key":"enter","key":"escape"}"#,
            r#"{"key":"enter","extra":true}"#,
        ] {
            assert!(parse_input(input).is_none(), "accepted {input}");
        }
    }

    #[test]
    fn rejects_invalid_request_objects() {
        for body in [
            format!(r#"{{{IDENTITY}}}"#),
            format!(r#"{{{IDENTITY},"input":{{"text":"x"}},"input":{{"key":"enter"}}}}"#),
            format!(r#"{{{IDENTITY},"input":{{"key":"enter"}},"paneId":"%6"}}"#),
            format!(r#"{{{IDENTITY},"input":{{"key":"enter"}},"extra":true}}"#),
            format!(r#"{{{IDENTITY},"input":{{"key":"enter"}}}} {{}}"#),
            r#"["$1","2-3","@4","%5",{"key":"enter"}]"#.to_owned(),
            r#"{"sessionId":"$1","generation":"2-3","windowId":"@4","input":{"key":"enter"}}"#.to_owned(),
            r#"{"sessionId":"$1","generation":"2-3","windowId":"@4","paneId":"%05","input":{"key":"enter"}}"#.to_owned(),
        ] {
            assert!(input_command(body.as_bytes()).is_none(), "accepted {body}");
        }
    }
}
