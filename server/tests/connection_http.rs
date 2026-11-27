//! HTTP and process-lifetime contract, not a Pi/tmux integration test.
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    process::{Child, Command, Stdio},
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

use serde_json::Value;

const BRIDGE: &str = env!("CARGO_BIN_EXE_glowkin-bridge");
const SNAPSHOT: &str = "/v1/connection/snapshot";
const READ_CHAT: &str = "/v1/workspace/read-pi-chat";
const EDIT_DRAFT: &str = "/v1/workspace/edit-pi-draft";
const TIMEOUT: Duration = Duration::from_secs(3);

struct Process(Child);

impl Drop for Process {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn start_bridge() -> (Process, SocketAddr) {
    start_bridge_with(Command::new(BRIDGE).env("PATH", "/dev/null"))
}

fn start_bridge_with(command: &mut Command) -> (Process, SocketAddr) {
    let reservation = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = reservation.local_addr().unwrap();
    drop(reservation);
    let mut bridge = Process(
        command
            .args(["--port", &address.port().to_string()])
            .stdout(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    let mut stdout = BufReader::new(bridge.0.stdout.take().unwrap());
    let (ready, started) = mpsc::channel();
    thread::spawn(move || {
        let mut line = String::new();
        let _ = ready.send(stdout.read_line(&mut line).map(|_| line));
    });
    assert_eq!(
        started.recv_timeout(TIMEOUT).unwrap().unwrap().trim(),
        format!("Bridge listening on {address}"),
    );
    (bridge, address)
}

fn request(address: SocketAddr, method: &str, path: &str) -> String {
    request_with_body(address, method, path, "", "")
}

fn request_with_body(
    address: SocketAddr,
    method: &str,
    path: &str,
    headers: &str,
    body: &str,
) -> String {
    let mut stream = TcpStream::connect_timeout(&address, TIMEOUT).unwrap();
    stream.set_read_timeout(Some(TIMEOUT)).unwrap();
    stream.set_write_timeout(Some(TIMEOUT)).unwrap();
    // Deliberately no credentials.
    write!(
        stream,
        "{method} {path} HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\n{headers}\r\n{body}"
    )
    .unwrap();
    let mut response = String::new();
    stream.read_to_string(&mut response).unwrap();
    response
}

fn status(response: &str) -> u16 {
    response.split_whitespace().nth(1).unwrap().parse().unwrap()
}

fn revision(address: SocketAddr) -> String {
    let response = request(address, "GET", SNAPSHOT);
    assert_eq!(status(&response), 200);
    let (headers, body) = response.split_once("\r\n\r\n").unwrap();
    assert!(
        headers
            .lines()
            .any(|line| line.eq_ignore_ascii_case("content-type: application/json"))
    );
    assert!(
        headers
            .lines()
            .any(|line| line.eq_ignore_ascii_case("cache-control: no-store"))
    );
    let snapshot: Value = serde_json::from_str(body).unwrap();
    assert_eq!(snapshot["protocolVersion"].as_u64(), Some(1));
    let revision = snapshot["revision"].as_str().unwrap();
    assert!(!revision.is_empty());
    revision.to_owned()
}

#[test]
fn anonymous_snapshot_is_stable_until_restart() {
    let (bridge, address) = start_bridge();
    let first = revision(address);
    assert_eq!(revision(address), first);
    drop(bridge);
    let (_new_bridge, new_address) = start_bridge();
    assert_ne!(revision(new_address), first);
}

#[test]
fn rejects_unknown_routes_and_unsupported_methods() {
    let (_bridge, address) = start_bridge();
    for (method, path, expected) in [
        ("GET", "/", 404),
        ("GET", "/v1/connection/snapshots", 404),
        ("GET", "/v2/connection/snapshot", 404),
        ("GET", "/v1/connection/snapshot?extra=1", 404),
        ("POST", "/v1/connection/disconnect", 404),
        ("POST", SNAPSHOT, 405),
        ("PUT", SNAPSHOT, 405),
        ("PATCH", SNAPSHOT, 405),
        ("DELETE", SNAPSHOT, 405),
        ("OPTIONS", SNAPSHOT, 501),
        ("OPTIONS", "/", 501),
    ] {
        let response = request(address, method, path);
        assert_eq!(status(&response), expected, "{method} {path}");
        if expected == 405 {
            let (headers, _) = response.split_once("\r\n\r\n").unwrap();
            assert!(
                headers
                    .lines()
                    .any(|line| line.eq_ignore_ascii_case("allow: GET")),
                "{method} {path}: expected Allow: GET, got {headers:?}"
            );
        }
    }
}

#[test]
fn client_disconnect_and_bridge_shutdown_leave_independent_process_running() {
    let (mut bridge, address) = start_bridge();
    let mut sentinel = Process(Command::new("sleep").arg("60").spawn().unwrap());
    assert_eq!(status(&request(address, "GET", SNAPSHOT)), 200);
    assert_eq!(status(&request(address, "GET", SNAPSHOT)), 200);
    assert!(sentinel.0.try_wait().unwrap().is_none());

    assert!(
        Command::new("kill")
            .args(["-INT", &bridge.0.id().to_string()])
            .status()
            .unwrap()
            .success()
    );
    let deadline = Instant::now() + TIMEOUT;
    loop {
        if let Some(status) = bridge.0.try_wait().unwrap() {
            assert!(status.success(), "bridge exited with {status}");
            break;
        }
        assert!(Instant::now() < deadline, "bridge did not shut down");
        thread::sleep(Duration::from_millis(10));
    }
    assert!(sentinel.0.try_wait().unwrap().is_none());
}

#[test]
fn pi_chat_http_checks_without_tmux() {
    // A valid request that reaches tmux must fail; no real tmux is in PATH.
    let (_bridge, address) = start_bridge_with(
        Command::new(BRIDGE)
            .env("PATH", "/dev/null")
            .arg("--enable-pane-input"),
    );
    let read = r#"{"sessionId":"$1","generation":"2-3","windowId":"@4","paneId":"%5"}"#;
    let edit = serde_json::json!({
        "sessionId": "$1", "generation": "2-3", "windowId": "@4", "paneId": "%5",
        "instanceId": "0123456789abcdef0123456789abcdef", "draftRevision": "9", "text": "π\n\t", "bridgeRevision": "stale",
    });
    let response = request(address, "GET", READ_CHAT);
    assert_chat_response(&response, 405);
    assert!(
        response
            .lines()
            .any(|line| line.eq_ignore_ascii_case("allow: POST"))
    );
    assert_chat_response(&request(address, "POST", EDIT_DRAFT), 415);
    assert_chat_response(
        &request_with_body(
            address,
            "POST",
            READ_CHAT,
            "Content-Type: application/json\r\n",
            "",
        ),
        400,
    );
    assert_chat_response(
        &post_chat(address, READ_CHAT, "Origin: http://local.test\r\n", read),
        403,
    );
    assert_chat_response(&post_chat(address, READ_CHAT, "", read), 503);
    assert_chat_response(
        &post_chat(address, READ_CHAT, "", r#"["$1","2-3","@4","%5"]"#),
        400,
    );
    assert_chat_response(
        &post_chat(
            address,
            READ_CHAT,
            "",
            &format!(r#"{},"instanceId":null}}"#, read.trim_end_matches('}')),
        ),
        400,
    );
    assert_chat_response(&post_chat(address, EDIT_DRAFT, "", &edit.to_string()), 409);
    let mut invalid = edit;
    for bad_text in ["\r".to_owned(), "\u{2028}".to_owned(), "😀".repeat(4097)] {
        invalid["text"] = bad_text.into();
        assert_chat_response(
            &post_chat(address, EDIT_DRAFT, "", &invalid.to_string()),
            400,
        );
    }
    invalid["text"] = "x".repeat(128 * 1024).into();
    assert_chat_response(
        &post_chat(address, EDIT_DRAFT, "", &invalid.to_string()),
        413,
    );

    let (_bridge, address) = start_bridge_with(Command::new(BRIDGE).env("PATH", "/dev/null"));
    assert_chat_response(&request(address, "POST", EDIT_DRAFT), 403);
}

fn post_chat(address: SocketAddr, path: &str, extra_headers: &str, body: &str) -> String {
    request_with_body(
        address,
        "POST",
        path,
        &format!(
            "Content-Type: application/json\r\nContent-Length: {}\r\n{extra_headers}",
            body.len()
        ),
        body,
    )
}

fn assert_chat_response(response: &str, expected: u16) {
    assert_eq!(status(response), expected);
    let (headers, body) = response.split_once("\r\n\r\n").unwrap();
    assert!(
        headers
            .lines()
            .any(|line| line.eq_ignore_ascii_case("cache-control: no-store"))
    );
    assert!(body.is_empty());
}

#[test]
fn cli_rejects_invalid_ports() {
    for port in ["0", "65536"] {
        let output = Command::new(BRIDGE)
            .args(["--port", port])
            .output()
            .unwrap();
        assert!(!output.status.success());
        assert!(String::from_utf8_lossy(&output.stderr).contains("--port"));
    }
}
