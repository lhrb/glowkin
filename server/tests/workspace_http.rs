//! Exercise HTTP against disposable tmux, including windows not created by the bridge.
use std::{
    collections::BTreeMap,
    fs::{self, DirBuilder},
    io::{BufRead, BufReader, Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    os::unix::fs::DirBuilderExt,
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

use serde_json::{Value, json};
use uuid::Uuid;

const SNAPSHOT: &str = "/v1/connection/snapshot";
const OPEN_SHELL: &str = "/v1/workspace/open-shell";
const TIMEOUT: Duration = Duration::from_secs(5);

#[test]
fn inventory_includes_pc_windows_and_keeps_their_ids_on_refresh() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-s",
        "work",
        "-n",
        "Existing editor",
        "/bin/sleep 300",
    ]);
    let session_id = tmux.run(&["list-sessions", "-F", "#{session_id}"]);
    let before = tmux.windows();
    let bridge = Bridge::start(&tmux);
    let first = bridge.workspace();
    assert_eq!(first["session"]["name"], "work", "{first}");
    assert_eq!(first["session"]["id"], session_id, "{first}");
    assert_eq!(ready_windows(&first), before);

    let editor_id = before.keys().next().unwrap();
    tmux.run(&["rename-window", "-t", editor_id, "Renamed editor"]);
    let new_id = tmux.run(&[
        "new-window",
        "-d",
        "-P",
        "-F",
        "#{window_id}",
        "-t",
        "work:",
        "-n",
        "PC terminal",
        "/bin/sleep 300",
    ]);
    let refreshed = bridge.workspace();
    assert_eq!(refreshed["session"]["id"], session_id, "{refreshed}");
    assert_eq!(
        ready_windows(&refreshed),
        BTreeMap::from([
            (editor_id.clone(), "Renamed editor".to_owned()),
            (new_id, "PC terminal".to_owned()),
        ]),
    );
}

#[test]
fn opening_a_shell_creates_one_interactive_home_window_and_leaves_panes_alive() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-s",
        "work",
        "-n",
        "existing",
        "-c",
        tmux.dir.to_str().unwrap(),
        "/bin/sh -c 'if IFS= read -r line; then touch existing-pane-received-input; fi; exec /bin/sleep 300'",
    ]);
    tmux.run(&["set-option", "-g", "default-shell", "/bin/sh"]);
    tmux.run(&[
        "set-option",
        "-t",
        "work",
        "default-command",
        "/bin/sleep 300",
    ]);
    let panes_before = tmux.panes();
    let before = tmux.windows();
    let mut bridge = Bridge::start(&tmux);
    let workspace = bridge.workspace();
    let target = open_target(&workspace);
    let browser_status = bridge.open(&target, Some("http://untrusted.example"));
    assert!(
        !(200..300).contains(&browser_status),
        "browser-origin POST returned {browser_status}"
    );
    assert_eq!(
        tmux.windows(),
        before,
        "browser-origin POST opened a window"
    );

    assert_eq!(bridge.open(&target, None), 204);
    let after = tmux.windows();
    assert_eq!(
        after.len(),
        before.len() + 1,
        "expected one new window: {after:?}"
    );
    let new_window = after.keys().find(|id| !before.contains_key(*id)).unwrap();
    assert_eq!(ready_windows(&bridge.workspace()), after);

    // Probe only the new pane. A sleeping process (the tmux default-command) cannot answer.
    tmux.run(&[
        "send-keys",
        "-t",
        new_window,
        "-l",
        "if test -t 0; then printf '%s\\n' \"$-\" \"$PWD\" > .glowkin-shell-probe; fi",
    ]);
    tmux.run(&["send-keys", "-t", new_window, "Enter"]);
    let probe = tmux.dir.join("home/.glowkin-shell-probe");
    let deadline = Instant::now() + TIMEOUT;
    let (flags, cwd) = loop {
        if let Ok(text) = fs::read_to_string(&probe) {
            let lines: Vec<_> = text.lines().collect();
            if lines.len() == 2 && text.ends_with('\n') {
                break (lines[0].to_owned(), PathBuf::from(lines[1]));
            }
        }
        assert!(
            Instant::now() < deadline,
            "new window {new_window} did not run a shell on a tty at HOME; panes: {}",
            tmux.panes()
        );
        thread::sleep(Duration::from_millis(10));
    };
    assert!(
        flags.contains('i'),
        "expected interactive shell, got flags {flags:?}"
    );
    assert_eq!(cwd, tmux.dir.join("home"));

    // Every HTTP request closed its connection. Stopping the bridge must also spare all panes.
    let panes_after = tmux.panes();
    for existing in panes_before.lines() {
        assert!(
            panes_after.lines().any(|line| line == existing),
            "lost existing pane {existing}: {panes_after}"
        );
    }
    assert_eq!(
        panes_after.lines().count(),
        panes_before.lines().count() + 1
    );
    bridge.stop();
    assert_eq!(
        tmux.panes(),
        panes_after,
        "bridge shutdown changed the running tmux panes"
    );
    assert!(
        !tmux.dir.join("existing-pane-received-input").exists(),
        "open-shell sent input to the existing pane"
    );
}

#[test]
fn rejects_multiple_missing_and_stale_targets_without_opening_elsewhere() {
    let tmux = Tmux::new();
    tmux.run(&["new-session", "-d", "-s", "original", "/bin/sleep 300"]);
    let bridge = Bridge::start(&tmux);
    let original = open_target(&bridge.workspace());

    tmux.run(&["new-session", "-d", "-s", "other", "/bin/sleep 300"]);
    assert_eq!(bridge.workspace()["status"], "multiple_sessions");
    assert_rejected_without_changes(&bridge, &tmux, &original);

    tmux.run(&["kill-session", "-t", "original"]);
    let other = open_target(&bridge.workspace());
    assert_ne!(original["sessionId"], other["sessionId"]);
    assert_rejected_without_changes(&bridge, &tmux, &original);

    tmux.run(&["set-option", "-s", "exit-empty", "off"]);
    tmux.run(&["kill-session", "-t", "other"]);
    assert_eq!(bridge.workspace()["status"], "no_session");
    assert_eq!(bridge.open(&other, None), 409);
    assert_eq!(
        bridge.workspace()["status"],
        "no_session",
        "POST must not start a session"
    );

    tmux.run(&["kill-server"]);
    let deadline = Instant::now() + TIMEOUT;
    while tmux.server_is_running() {
        assert!(
            Instant::now() < deadline,
            "private tmux server did not stop"
        );
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(bridge.open(&other, None), 409, "tmux server is unavailable");
    assert!(
        !tmux.server_is_running(),
        "POST must not start a tmux server"
    );

    // A new tmux server can reuse the original session ID at the same socket.
    tmux.run(&["new-session", "-d", "-s", "original", "/bin/sleep 300"]);
    let replacement = open_target(&bridge.workspace());
    assert_eq!(replacement["sessionId"], original["sessionId"]);
    assert_ne!(replacement["generation"], original["generation"]);
    assert_rejected_without_changes(&bridge, &tmux, &original);
}

fn assert_rejected_without_changes(bridge: &Bridge, tmux: &Tmux, target: &Value) {
    let before = tmux.panes();
    assert_eq!(bridge.open(target, None), 409, "target: {target}");
    assert_eq!(tmux.panes(), before, "rejected POST changed tmux panes");
}

fn open_target(workspace: &Value) -> Value {
    assert_eq!(workspace["status"], "ready", "{workspace}");
    let session = &workspace["session"];
    let id = session["id"].as_str().expect("ready workspace session ID");
    let generation = session["generation"]
        .as_str()
        .expect("ready workspace generation");
    assert!(!generation.is_empty(), "empty generation: {workspace}");
    json!({"sessionId": id, "generation": generation})
}

fn ready_windows(workspace: &Value) -> BTreeMap<String, String> {
    open_target(workspace);
    let windows = workspace["session"]["windows"]
        .as_array()
        .expect("ready workspace windows");
    let by_id: BTreeMap<_, _> = windows
        .iter()
        .map(|window| {
            (
                window["id"].as_str().unwrap().to_owned(),
                window["name"].as_str().unwrap().to_owned(),
            )
        })
        .collect();
    assert_eq!(
        by_id.len(),
        windows.len(),
        "duplicate window IDs: {windows:?}"
    );
    by_id
}

struct Tmux {
    dir: PathBuf,
    socket: PathBuf,
}

impl Tmux {
    fn new() -> Self {
        // /tmp keeps the Unix socket path short. Create private directories atomically.
        let dir = PathBuf::from(format!("/tmp/glowkin-{}", Uuid::new_v4().simple()));
        let uid = Command::new("id").arg("-u").output().unwrap();
        assert!(uid.status.success());
        let socket_dir = dir.join(format!(
            "tmux-{}",
            String::from_utf8(uid.stdout).unwrap().trim()
        ));
        let mut private_dir = DirBuilder::new();
        private_dir.mode(0o700).create(&dir).unwrap();
        let tmux = Self {
            dir,
            socket: socket_dir.join("default"),
        };
        private_dir.create(&socket_dir).unwrap();
        private_dir.create(tmux.dir.join("home")).unwrap();
        tmux
    }

    fn command(&self, program: &str) -> Command {
        let mut command = Command::new(program);
        command
            .current_dir(&self.dir)
            .env("TMUX_TMPDIR", &self.dir)
            .env("HOME", self.dir.join("home"))
            .env("XDG_CONFIG_HOME", self.dir.join("home"))
            .env("SHELL", "/bin/sh")
            .env_remove("ENV")
            .env_remove("BASH_ENV")
            .env_remove("TMUX")
            .env_remove("TMUX_PANE");
        command
    }

    fn tmux(&self) -> Command {
        let mut command = self.command("tmux");
        // Explicit for setup/teardown; the bridge discovers this same private default socket.
        command
            .arg("-S")
            .arg(&self.socket)
            .args(["-f", "/dev/null"]);
        command
    }

    fn run(&self, args: &[&str]) -> String {
        let output = self.tmux().args(args).output().unwrap();
        assert!(
            output.status.success(),
            "tmux {args:?}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        String::from_utf8(output.stdout)
            .unwrap()
            .trim_end_matches('\n')
            .to_owned()
    }

    fn server_is_running(&self) -> bool {
        self.tmux()
            .arg("list-sessions")
            .output()
            .unwrap()
            .status
            .success()
    }

    fn windows(&self) -> BTreeMap<String, String> {
        self.run(&["list-windows", "-a", "-F", "#{window_id}\t#{window_name}"])
            .lines()
            .map(|line| {
                let (id, name) = line.split_once('\t').unwrap();
                (id.to_owned(), name.to_owned())
            })
            .collect()
    }

    fn panes(&self) -> String {
        self.run(&[
            "list-panes",
            "-a",
            "-F",
            "#{session_id}:#{window_id}:#{pane_id} pid=#{pane_pid} dead=#{pane_dead}",
        ])
    }
}

impl Drop for Tmux {
    fn drop(&mut self) {
        let _ = self.tmux().arg("kill-server").output();
        let _ = fs::remove_dir_all(&self.dir);
    }
}

struct Bridge {
    child: Child,
    address: SocketAddr,
}

impl Bridge {
    fn start(tmux: &Tmux) -> Self {
        let reservation = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = reservation.local_addr().unwrap();
        drop(reservation);
        let child = tmux
            .command(env!("CARGO_BIN_EXE_glowkin-bridge"))
            .args(["--port", &address.port().to_string()])
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let mut bridge = Self { child, address };
        let mut stdout = BufReader::new(bridge.child.stdout.take().unwrap());
        let (ready, started) = mpsc::channel();
        thread::spawn(move || {
            let mut line = String::new();
            let _ = ready.send(stdout.read_line(&mut line).map(|_| line));
        });
        assert_eq!(
            started.recv_timeout(TIMEOUT).unwrap().unwrap().trim(),
            format!("Bridge listening on {address}")
        );
        bridge
    }

    fn stop(&mut self) {
        assert!(
            Command::new("kill")
                .args(["-INT", &self.child.id().to_string()])
                .status()
                .unwrap()
                .success()
        );
        let deadline = Instant::now() + TIMEOUT;
        loop {
            if let Some(status) = self.child.try_wait().unwrap() {
                assert!(status.success(), "bridge exited with {status}");
                return;
            }
            assert!(Instant::now() < deadline, "bridge did not stop on SIGINT");
            thread::sleep(Duration::from_millis(10));
        }
    }

    fn workspace(&self) -> Value {
        let (status, body) = self.request("GET", SNAPSHOT, "", "");
        assert_eq!(status, 200, "{body}");
        let snapshot: Value = serde_json::from_str(&body).unwrap();
        assert!(
            snapshot["workspace"].is_object(),
            "GET snapshot lacks a workspace object: {snapshot}"
        );
        snapshot["workspace"].clone()
    }

    fn open(&self, target: &Value, origin: Option<&str>) -> u16 {
        let body = target.to_string();
        let mut headers = format!(
            "Content-Type: application/json\r\nContent-Length: {}\r\n",
            body.len()
        );
        if let Some(origin) = origin {
            headers.push_str(&format!("Origin: {origin}\r\n"));
        }
        self.request("POST", OPEN_SHELL, &headers, &body).0
    }

    fn request(&self, method: &str, path: &str, headers: &str, body: &str) -> (u16, String) {
        let address = self.address;
        let mut stream = TcpStream::connect_timeout(&address, TIMEOUT).unwrap();
        stream.set_read_timeout(Some(TIMEOUT)).unwrap();
        stream.set_write_timeout(Some(TIMEOUT)).unwrap();
        write!(stream, "{method} {path} HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\n{headers}\r\n{body}").unwrap();
        let mut response = String::new();
        stream.read_to_string(&mut response).unwrap();
        let (head, body) = response
            .split_once("\r\n\r\n")
            .unwrap_or_else(|| panic!("invalid response to {method} {path}: {response:?}"));
        (
            head.split_whitespace().nth(1).unwrap().parse().unwrap(),
            body.to_owned(),
        )
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
