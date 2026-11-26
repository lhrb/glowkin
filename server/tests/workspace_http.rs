//! Exercise HTTP against disposable tmux, including windows not created by the bridge.
use std::{
    collections::BTreeMap,
    fs::{self, DirBuilder},
    io::{BufRead, BufReader, Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    os::unix::fs::{DirBuilderExt, PermissionsExt},
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
const READ_PANE: &str = "/v1/workspace/read-pane";
const SEND_INPUT: &str = "/v1/workspace/send-input";
const ENABLE_PANE_INPUT: &str = "--enable-pane-input";
const TIMEOUT: Duration = Duration::from_secs(5);

#[test]
fn snapshot_advertises_the_input_flag_as_a_boolean() {
    let tmux = Tmux::new();
    for enabled in [false, true] {
        let bridge = if enabled {
            Bridge::start_with_input(&tmux)
        } else {
            Bridge::start(&tmux)
        };
        let (status, body) = bridge.request("GET", SNAPSHOT, "", "");
        assert_eq!(status, 200);
        let snapshot: Value = serde_json::from_str(&body).unwrap();
        assert_eq!(snapshot["paneInputEnabled"], enabled);
    }
}

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

#[test]
fn pane_capture_checks_exact_membership_single_pane_and_server_lifetime() {
    let tmux = Tmux::new();
    let setup = || {
        tmux.run(&[
            "new-session",
            "-d",
            "-s",
            "work",
            "-n",
            "focus",
            "/bin/sleep 300",
        ]);
        tmux.run(&[
            "new-window",
            "-d",
            "-t",
            "work:",
            "-n",
            "target",
            "/bin/sleep 300",
        ]);
    };
    setup();
    let bridge = Bridge::start(&tmux);
    let workspace = bridge.workspace();
    let mut exact = open_target(&workspace);
    exact["windowId"] =
        json!(tmux.run(&["display-message", "-p", "-t", "work:target", "#{window_id}"]));
    exact["paneId"] =
        json!(tmux.run(&["display-message", "-p", "-t", "work:target", "#{pane_id}"]));
    assert_eq!(bridge.read(&exact, "").0, 200);
    let target = pane_target(&workspace, "target");
    assert_eq!(target, exact);
    let focus = pane_target(&workspace, "focus");

    for (field, value) in [
        ("sessionId", json!("$99")),
        ("windowId", json!("@99")),
        ("windowId", focus["windowId"].clone()),
        ("paneId", json!("%99")),
        ("paneId", focus["paneId"].clone()),
    ] {
        let mut wrong = target.clone();
        wrong[field] = value;
        assert_eq!(
            bridge.read(&wrong, ""),
            (409, String::new()),
            "wrong {field}: {wrong}"
        );
    }

    let wid = target["windowId"].as_str().unwrap();
    let split = tmux.run(&[
        "split-window",
        "-d",
        "-P",
        "-F",
        "#{pane_id}",
        "-t",
        wid,
        "/bin/sleep 300",
    ]);
    assert!(window_named(&bridge.workspace(), "target")["paneId"].is_null());
    assert_eq!(bridge.read(&target, "").0, 409);
    // A replacement in the same window must not inherit the read identity.
    tmux.run(&["kill-pane", "-t", target["paneId"].as_str().unwrap()]);
    assert_eq!(bridge.read(&target, "").0, 409);
    let replacement_pane = pane_target(&bridge.workspace(), "target");
    assert_eq!(replacement_pane["paneId"], split);
    assert_eq!(bridge.read(&replacement_pane, "").0, 200);

    tmux.run(&["new-session", "-d", "-s", "other", "/bin/sleep 300"]);
    assert_eq!(bridge.workspace()["status"], "multiple_sessions");
    assert_eq!(bridge.read(&replacement_pane, "").0, 409);
    tmux.run(&["move-window", "-s", wid, "-t", "other:"]);
    tmux.run(&["kill-session", "-t", "work"]);
    assert_eq!(bridge.read(&replacement_pane, "").0, 409);
    let moved = pane_target(&bridge.workspace(), "target");
    assert_eq!(moved["windowId"], wid);
    assert_eq!(moved["paneId"], split);
    assert_eq!(bridge.read(&moved, "").0, 200);
    tmux.run(&["kill-window", "-t", wid]);
    assert_eq!(bridge.read(&moved, "").0, 409);

    tmux.run(&["kill-server"]);
    let deadline = Instant::now() + TIMEOUT;
    while tmux.server_is_running() {
        assert!(Instant::now() < deadline);
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(bridge.read(&target, "").0, 409);
    assert!(!tmux.server_is_running());
    setup();
    let new = pane_target(&bridge.workspace(), "target");
    for field in ["sessionId", "windowId", "paneId"] {
        assert_eq!(new[field], target[field]);
    }
    assert_ne!(new["generation"], target["generation"]);
    assert_eq!(bridge.read(&target, ""), (409, String::new()));
    assert_eq!(bridge.read(&new, "").0, 200);
}

#[test]
fn pane_capture_is_plain_visible_screen_only_and_does_not_change_focus_or_input() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-s",
        "work",
        "-n",
        "focus",
        "/bin/sleep 300",
    ]);
    tmux.run(&[
        "new-window",
        "-d",
        "-t",
        "work:",
        "-n",
        "target",
        "/bin/sh",
        "-c",
        r#"i=0; while [ $i -lt 60 ]; do printf 'history-only\n'; i=$((i+1)); done
        printf '\033[2J\033[Hvisible line\n    indented line\n\033[31mcolored\033[0m\n'
        if IFS= read -r line; then printf '%s' "$line" > pane-input; fi
        exec /bin/sleep 300"#,
    ]);
    let bridge = Bridge::start(&tmux);
    let workspace = bridge.workspace();
    let target = pane_target(&workspace, "target");
    let pane = target["paneId"].as_str().unwrap();
    let focus = || {
        tmux.run(&[
            "display-message",
            "-p",
            "-t",
            "work:",
            "#{window_id}:#{pane_id}",
        ])
    };
    let before = focus();
    assert_eq!(
        before,
        format!(
            "{}:{}",
            workspace["session"]["windows"][0]["id"].as_str().unwrap(),
            workspace["session"]["windows"][0]["paneId"]
                .as_str()
                .unwrap()
        )
    );
    assert!(
        !before.ends_with(&format!(":{pane}")),
        "target must not have focus"
    );
    tmux.wait_for_screen(pane, "colored");
    tmux.run(&["send-keys", "-l", "-t", pane, "pending input"]);
    tmux.wait_for_screen(pane, "pending input");
    let text = target.to_string();
    let (status, headers, body) = bridge.raw_request(
        "POST",
        READ_PANE,
        &format!(
            "Content-Type: application/json\r\nContent-Length: {}\r\n",
            text.len()
        ),
        &text,
    );
    assert_eq!(status, 200);
    assert!(headers.contains("\r\ncontent-type: text/plain; charset=utf-8\r\n"));
    assert!(headers.contains("\r\ncache-control: no-store\r\n"));
    assert!(headers.contains("\r\nx-content-type-options: nosniff\r\n"));
    assert!(!headers.contains("access-control-"));
    assert!(body.contains("visible line\n    indented line\ncolored\npending input\n"));
    assert!(!body.contains("\x1b"));
    assert!(!body.contains("history-only"), "must exclude scrollback");
    assert!(!bridge.workspace().to_string().contains("indented line"));
    assert_eq!(focus(), before);
    assert!(
        !tmux.dir.join("pane-input").exists(),
        "read must not submit PC input"
    );
    tmux.run(&["send-keys", "-t", pane, "Enter"]);
    let deadline = Instant::now() + TIMEOUT;
    let input = loop {
        if let Ok(input) = fs::read_to_string(tmux.dir.join("pane-input"))
            && !input.is_empty()
        {
            break input;
        }
        assert!(Instant::now() < deadline);
        thread::sleep(Duration::from_millis(10));
    };
    assert_eq!(input, "pending input");
    let blank = pane_target(&workspace, "focus");
    let (status, screen) = bridge.read(&blank, "");
    assert_eq!(status, 200);
    assert!(screen.trim().is_empty());

    for headers in [
        "Origin: null\r\n",
        "Referer: http://browser.example/\r\n",
        "Sec-Fetch-Site: same-origin\r\n",
    ] {
        assert_eq!(bridge.read(&target, headers), (403, String::new()));
    }
    assert_eq!(bridge.request("GET", READ_PANE, "", "").0, 405);
    assert_eq!(bridge.request("OPTIONS", READ_PANE, "", "").0, 501);
    assert_eq!(
        bridge
            .request("POST", &format!("{READ_PANE}?target=bad"), "", "")
            .0,
        404
    );
    assert_eq!(
        bridge
            .request(
                "POST",
                READ_PANE,
                "Content-Type: text/plain\r\nContent-Length: 2\r\n",
                "{}"
            )
            .0,
        415
    );
    assert_eq!(
        bridge
            .request(
                "POST",
                READ_PANE,
                "Content-Type: application/json\r\nContent-Length: 257\r\n",
                &" ".repeat(257)
            )
            .0,
        413
    );
    let mut invalid = target;
    for pane in ["%01", "%1; display-message -p oops", "@1", "%4294967296"] {
        invalid["paneId"] = json!(pane);
        assert_eq!(bridge.read(&invalid, ""), (400, String::new()));
    }
}

#[test]
fn pane_read_cannot_trigger_configured_tmux_hooks() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-s",
        "work",
        "-n",
        "focus",
        "/bin/sleep 300",
    ]);
    tmux.run(&[
        "new-window",
        "-d",
        "-t",
        "work:",
        "-n",
        "target",
        "/bin/sleep 300",
    ]);
    let bridge = Bridge::start(&tmux);
    let target = pane_target(&bridge.workspace(), "target");
    let wid = target["windowId"].as_str().unwrap();
    let pane = target["paneId"].as_str().unwrap();
    let current = || tmux.run(&["display-message", "-p", "-t", "work:", "#{window_id}"]);
    let before = current();
    let change_focus = format!("select-window -t 'work:{wid}'");
    for options in [
        vec!["-g"],
        vec!["-t", "work"],
        vec!["-w", "-t", wid],
        vec!["-p", "-t", pane],
    ] {
        let hook = "after-capture-pane[77]";
        let mut set = vec!["set-hook"];
        set.extend(&options);
        set.extend([hook, &change_focus]);
        tmux.run(&set);
        assert!(window_named(&bridge.workspace(), "target")["paneId"].is_null());
        let (status, body) = bridge.read(&target, "");
        assert_eq!(current(), before, "pane read ran {hook} at {options:?}");
        assert_eq!(status, 409);
        assert!(body.is_empty());
        let mut unset = vec!["set-hook", "-u"];
        unset.extend(options);
        unset.push(hook);
        tmux.run(&unset);
    }
    assert_eq!(bridge.read(&target, "").0, 200);
}

#[test]
fn oversized_visible_capture_fails_without_partial_content_and_recovers() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-x",
        "300",
        "-y",
        "300",
        "-s",
        "work",
        "-n",
        "wide",
        "/bin/sh",
        "-c",
        r"i=0; while [ $i -lt 290 ]; do printf '%0290d\n' $i; i=$((i+1)); done
        printf 'screen-ready\n'; exec /bin/sleep 300",
    ]);
    let bridge = Bridge::start(&tmux);
    let target = pane_target(&bridge.workspace(), "wide");
    let pane = target["paneId"].as_str().unwrap();
    tmux.wait_for_screen(pane, "screen-ready");
    assert!(tmux.run(&["capture-pane", "-p", "-t", pane]).len() > 65536);
    assert_eq!(bridge.read(&target, ""), (503, String::new()));
    tmux.run(&["resize-window", "-t", "work:wide", "-x", "80", "-y", "24"]);
    let (status, body) = bridge.read(&target, "");
    assert_eq!(status, 200);
    assert!(body.len() <= 65536);
}

#[test]
fn optional_snapshot_fields_preserve_legacy_inventory_at_both_limits() {
    const LEGACY: &str =
        "#{pid}\t#{start_time}\t#{session_id}\t#{window_id}\t#{session_name}\t#{window_name}";
    const WITH_PANES: &str = "#{pid}\t#{start_time}\t#{session_id}\t#{window_id}\t#{pane_id}\t#{session_name}\t#{window_name}";
    for (json_limit, panes_fit) in [(false, false), (true, false), (true, true)] {
        let tmux = Tmux::new();
        let name = if json_limit {
            "\"".repeat(2500)
        } else {
            "w".repeat(5000)
        };
        tmux.run(&[
            "new-session",
            "-d",
            "-s",
            "work",
            "-n",
            &name,
            "/bin/sleep 300",
        ]);
        for _ in 0..10 {
            tmux.run(&[
                "new-window",
                "-d",
                "-t",
                "work:",
                "-n",
                &name,
                "/bin/sleep 300",
            ]);
        }
        let last = tmux.run(&[
            "new-window",
            "-d",
            "-P",
            "-F",
            "#{window_id}",
            "-t",
            "work:",
            "-n",
            "z",
            "/bin/sleep 300",
        ]);
        if !json_limit {
            // tmux repeats the session name per row; JSON includes it only once.
            tmux.run(&["rename-session", "-t", "work", &"s".repeat(80)]);
        }
        let bridge = Bridge::start_with_input(&tmux);
        let (_, body) = bridge.request("GET", SNAPSHOT, "", "");
        let mut snapshot: Value = serde_json::from_str(&body).unwrap();
        snapshot.as_object_mut().unwrap().remove("paneInputEnabled");
        if !panes_fit {
            for window in snapshot["workspace"]["session"]["windows"]
                .as_array_mut()
                .unwrap()
            {
                assert!(window.as_object_mut().unwrap().remove("paneId").is_some());
            }
        }
        let bytes = |format| tmux.run(&["list-windows", "-a", "-F", format]).len() + 1;
        let legacy_size = if json_limit {
            snapshot.to_string().len()
        } else {
            bytes(LEGACY)
        };
        tmux.run(&[
            "rename-window",
            "-t",
            &last,
            &"z".repeat(65536 - legacy_size - 8 + 1),
        ]);
        if json_limit {
            assert!(bytes(WITH_PANES) < 65536);
        } else {
            assert_eq!(bytes(LEGACY), 65536 - 8);
            assert!(bytes(WITH_PANES) > 65536);
        }
        let (status, body) = bridge.request("GET", SNAPSHOT, "", "");
        assert_eq!(status, 200);
        assert!(body.len() <= 65536);
        let snapshot: Value = serde_json::from_str(&body).unwrap();
        if json_limit {
            assert_eq!(body.len(), 65536 - 8);
            assert!(snapshot.get("paneInputEnabled").is_none());
        } else {
            assert_eq!(snapshot["paneInputEnabled"], true);
        }
        let workspace = &snapshot["workspace"];
        assert_eq!(ready_windows(workspace), tmux.windows());
        assert!(
            workspace["session"]["windows"]
                .as_array()
                .unwrap()
                .iter()
                .all(|w| w.get("paneId").is_some() == panes_fit)
        );
        assert_eq!(bridge.open(&open_target(workspace), None), 204);
    }
}

#[test]
fn stalled_tmux_capture_times_out_and_the_next_read_succeeds() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-s",
        "work",
        "-n",
        "focus",
        "/bin/sleep 300",
    ]);
    let bridge = Bridge::start_with_tmux_wrapper(
        &tmux,
        &[],
        r#"#!/bin/sh
case "$*" in
    *"capture-pane -p"*) if [ ! -e stalled ]; then : > stalled; exec /bin/sleep 15; fi ;;
esac
PATH=${PATH#*:}; export PATH
exec tmux "$@"
"#,
    );
    let target = pane_target(&bridge.workspace(), "focus");
    assert_eq!(bridge.read(&target, ""), (503, String::new()));
    assert!(tmux.dir.join("stalled").exists());
    assert_eq!(bridge.read(&target, "").0, 200);
}

#[test]
fn pane_input_is_off_by_default() {
    let tmux = Tmux::new();
    tmux.start_reader(
        &["new-session", "-d", "-s", "work", "-n", "target"],
        "reader",
    );
    let bridge = Bridge::start(&tmux);
    let target = pane_target(&bridge.workspace(), "target");
    assert_eq!(
        bridge.send_input(&target, json!({"text": "blocked"}), ""),
        (403, String::new())
    );
    tmux.assert_reader_received_only_probe(&target, "reader");
}

#[test]
fn pane_input_sends_exact_text_and_keys_without_enter_or_focus_change() {
    let tmux = Tmux::new();
    tmux.start_reader(
        &["new-session", "-d", "-s", "work", "-n", "focus"],
        "focus-reader",
    );
    tmux.start_reader(
        &["new-window", "-d", "-t", "work:", "-n", "target"],
        "reader",
    );
    let bridge = Bridge::start_with_input(&tmux);
    let workspace = bridge.workspace();
    let target = pane_target(&workspace, "target");
    let focused = pane_target(&workspace, "focus");
    let focus = || {
        tmux.run(&[
            "display-message",
            "-p",
            "-t",
            "work:",
            "#{window_id}:#{pane_id}",
        ])
    };
    let before = focus();
    assert_eq!(
        before,
        format!(
            "{}:{}",
            focused["windowId"].as_str().unwrap(),
            focused["paneId"].as_str().unwrap()
        )
    );

    let mut expected = Vec::new();
    for text in [
        "  Grüße 🙂 東京 ",
        ";",
        "-t",
        "--",
        r#"  '" \n \e \033 \; $HOME $(touch unexpected) `touch unexpected` #{pane_id} ;  "#,
    ] {
        assert_eq!(
            bridge.send_input(&target, json!({"text": text}), ""),
            (204, String::new()),
            "{text:?}"
        );
        expected.extend_from_slice(text.as_bytes());
    }
    tmux.assert_reader_bytes("reader", &expected);
    for (key, bytes) in [
        ("enter", b"\r".as_slice()),
        ("escape", b"\x1b"),
        ("up", b"\x1b[A"),
        ("down", b"\x1b[B"),
        ("left", b"\x1b[D"),
        ("right", b"\x1b[C"),
        ("backspace", b"\x7f"),
        ("ctrl-o", b"\x0f"),
    ] {
        assert_eq!(
            bridge.send_input(&target, json!({"key": key}), ""),
            (204, String::new()),
            "{key}"
        );
        expected.extend_from_slice(bytes);
        tmux.assert_reader_bytes("reader", &expected);
    }
    tmux.send_reader_probe(&target);
    expected.extend_from_slice(b"probe");
    tmux.assert_reader_bytes("reader", &expected);
    tmux.assert_reader_received_only_probe(&focused, "focus-reader");
    assert!(!tmux.dir.join("unexpected").exists());
    assert_eq!(focus(), before);
    let (status, screen) = bridge.read(&target, "");
    assert_eq!(status, 200);
    assert!(screen.contains("reader-ready"));
    assert!(!screen.contains("Grüße"), "the reader did not echo input");
}

#[test]
fn pane_input_enforces_payload_and_mutation_request_limits() {
    let tmux = Tmux::new();
    tmux.start_reader(
        &["new-session", "-d", "-s", "work", "-n", "target"],
        "reader",
    );
    let bridge = Bridge::start_with_input(&tmux);
    let target = pane_target(&bridge.workspace(), "target");
    for input in [
        json!(null),
        json!("enter"),
        json!({}),
        json!({"text": "", "key": "enter"}),
        json!({"text": "blocked", "extra": false}),
        json!({"text": 1}),
        json!({"text": ""}),
        json!({"text": "é".repeat(129)}),
        json!({"key": "Enter"}),
        json!({"key": "C-o"}),
        json!({"key": "tab"}),
    ] {
        assert_eq!(
            bridge.send_input(&target, input.clone(), ""),
            (400, String::new()),
            "{input}"
        );
    }
    for invalid in [
        '\0', '\n', '\r', '\t', '\x1b', '\x7f', '\u{85}', '\u{2028}', '\u{2029}',
    ] {
        assert_eq!(
            bridge.send_input(
                &target,
                json!({"text": format!("before{invalid}after")}),
                ""
            ),
            (400, String::new()),
            "{invalid:?}"
        );
    }
    let mut invalid_target = target.clone();
    invalid_target["paneId"] = json!("%01");
    assert_eq!(
        bridge
            .send_input(&invalid_target, json!({"key": "enter"}), "")
            .0,
        400
    );
    invalid_target = target.clone();
    invalid_target["extra"] = json!(false);
    assert_eq!(
        bridge
            .send_input(&invalid_target, json!({"key": "enter"}), "")
            .0,
        400
    );
    for header in [
        "Origin: null\r\n",
        "Referer: http://browser.example/\r\n",
        "Sec-Fetch-Mode: cors\r\n",
    ] {
        assert_eq!(
            bridge.send_input(&target, json!({"text": "blocked"}), header),
            (403, String::new())
        );
    }

    let send = |body: &str, headers: &str| bridge.request("POST", SEND_INPUT, headers, body);
    let fixed_length = |body: &str| {
        format!(
            "Content-Type: application/json\r\nContent-Length: {}\r\n",
            body.len()
        )
    };
    let mut request = target;
    request["input"] = json!({"text": "blocked"});
    let body = request.to_string();
    let length = body.len();
    for headers in [
        format!("Content-Length: {length}\r\n"),
        format!("Content-Type: application/json; charset=utf-8\r\nContent-Length: {length}\r\n"),
        format!("{}Content-Type: application/json\r\n", fixed_length(&body)),
    ] {
        assert_eq!(send(&body, &headers), (415, String::new()), "{headers:?}");
    }
    assert_eq!(
        send("", "Content-Type: application/json\r\n"),
        (400, String::new())
    );
    assert_eq!(
        send(
            &format!("{length:x}\r\n{body}\r\n0\r\n\r\n"),
            "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n"
        ),
        (400, String::new())
    );
    let duplicate = body.replace("\"text\":", "\"text\":\"duplicate\",\"text\":");
    assert_eq!(
        send(&duplicate, &fixed_length(&duplicate)),
        (400, String::new())
    );

    let max_text = "é".repeat(128);
    request["input"] = json!({"text": max_text});
    let body = request.to_string();
    let max_body = format!("{body}{}", " ".repeat(2048 - body.len()));
    let too_large = format!("{max_body} ");
    assert_eq!(
        send(&too_large, &fixed_length(&too_large)),
        (413, String::new())
    );
    assert_eq!(
        send(&max_body, &fixed_length(&max_body)),
        (204, String::new())
    );
    tmux.assert_reader_bytes("reader", max_text.as_bytes());
}

#[test]
fn pane_input_rejects_stale_and_ineligible_topology_without_retargeting() {
    let tmux = Tmux::new();
    let setup = |focus, target| {
        tmux.start_reader(&["new-session", "-d", "-s", "work", "-n", "focus"], focus);
        tmux.start_reader(&["new-window", "-d", "-t", "work:", "-n", "target"], target);
    };
    setup("focus-reader", "original-reader");
    let bridge = Bridge::start_with_input(&tmux);
    let workspace = bridge.workspace();
    let target = pane_target(&workspace, "target");
    let focus = pane_target(&workspace, "focus");
    let reject = |target: &Value, scenario: &str| {
        assert_eq!(
            bridge.send_input(target, json!({"text": "blocked"}), ""),
            (409, String::new()),
            "{scenario}: {target}"
        );
    };
    for (field, value) in [
        ("sessionId", json!("$99")),
        ("windowId", json!("@99")),
        ("windowId", focus["windowId"].clone()),
        ("paneId", json!("%99")),
        ("paneId", focus["paneId"].clone()),
    ] {
        let mut wrong = target.clone();
        wrong[field] = value;
        reject(&wrong, field);
    }

    let wid = target["windowId"].as_str().unwrap();
    let split = tmux.start_reader(&["split-window", "-d", "-t", wid], "replacement-reader");
    reject(&target, "split window");
    tmux.assert_reader_received_only_probe(&target, "original-reader");
    tmux.assert_reader_received_only_probe(&focus, "focus-reader");
    tmux.run(&["kill-pane", "-t", target["paneId"].as_str().unwrap()]);
    reject(&target, "replaced pane in the same window");
    let replacement = pane_target(&bridge.workspace(), "target");
    assert_eq!(replacement["paneId"], split);

    tmux.start_reader(
        &["new-session", "-d", "-s", "other", "-n", "other"],
        "other-reader",
    );
    reject(&replacement, "multiple sessions");
    tmux.run(&["move-window", "-s", wid, "-t", "other:"]);
    tmux.run(&["kill-session", "-t", "work"]);
    reject(&replacement, "pane moved to another session");
    let workspace = bridge.workspace();
    let moved = pane_target(&workspace, "target");
    assert_eq!(moved["paneId"], split);
    assert_ne!(moved["sessionId"], replacement["sessionId"]);
    tmux.assert_reader_received_only_probe(&moved, "replacement-reader");
    tmux.assert_reader_received_only_probe(&pane_target(&workspace, "other"), "other-reader");
    tmux.run(&["kill-window", "-t", wid]);
    reject(&moved, "window gone");

    tmux.run(&["kill-server"]);
    let deadline = Instant::now() + TIMEOUT;
    while tmux.server_is_running() {
        assert!(
            Instant::now() < deadline,
            "private tmux server did not stop"
        );
        thread::sleep(Duration::from_millis(10));
    }
    reject(&target, "server gone");
    assert!(!tmux.server_is_running(), "POST started a tmux server");
    setup("new-focus-reader", "new-target-reader");
    let workspace = bridge.workspace();
    let restarted = pane_target(&workspace, "target");
    for field in ["sessionId", "windowId", "paneId"] {
        assert_eq!(restarted[field], target[field]);
    }
    assert_ne!(restarted["generation"], target["generation"]);
    reject(&target, "reused IDs on a new server");
    tmux.assert_reader_received_only_probe(&restarted, "new-target-reader");
    tmux.assert_reader_received_only_probe(&pane_target(&workspace, "focus"), "new-focus-reader");
}

#[test]
fn pane_input_rejects_input_disabled_modal_and_dead_panes() {
    let tmux = Tmux::new();
    tmux.start_reader(
        &["new-session", "-d", "-s", "work", "-n", "focus"],
        "focus-reader",
    );
    let pane = tmux.start_reader(
        &["new-window", "-d", "-t", "work:", "-n", "target"],
        "reader",
    );
    let bridge = Bridge::start_with_input(&tmux);
    let workspace = bridge.workspace();
    let target = pane_target(&workspace, "target");
    let reject = |input: Value, scenario: &str| {
        assert_eq!(
            bridge.send_input(&target, input, ""),
            (409, String::new()),
            "{scenario}"
        );
    };
    let state = |format| tmux.run(&["display-message", "-p", "-t", &pane, format]);

    tmux.run(&["select-pane", "-d", "-t", &pane]);
    assert_eq!(state("#{pane_input_off}"), "1");
    reject(json!({"text": "blocked"}), "input disabled");
    assert_eq!(state("#{pane_input_off}"), "1");
    tmux.run(&["select-pane", "-e", "-t", &pane]);

    tmux.run(&["copy-mode", "-t", &pane]);
    assert_eq!(state("#{pane_in_mode}"), "1");
    reject(json!({"key": "escape"}), "copy mode");
    assert_eq!(state("#{pane_in_mode}"), "1", "POST drove tmux UI");
    tmux.run(&["send-keys", "-X", "-t", &pane, "cancel"]);
    tmux.assert_reader_received_only_probe(&target, "reader");

    tmux.run(&["set-option", "-p", "-t", &pane, "remain-on-exit", "on"]);
    tmux.run(&["respawn-pane", "-k", "-t", &pane, "/bin/false"]);
    let deadline = Instant::now() + TIMEOUT;
    while state("#{pane_dead}") != "1" {
        assert!(Instant::now() < deadline, "pane did not exit");
        thread::sleep(Duration::from_millis(10));
    }
    let before = tmux.panes();
    reject(json!({"text": "blocked"}), "dead pane");
    assert_eq!(tmux.panes(), before);
    tmux.assert_reader_received_only_probe(&pane_target(&workspace, "focus"), "focus-reader");
}

#[test]
fn pane_input_cannot_trigger_configured_send_keys_hooks() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-s",
        "work",
        "-n",
        "focus",
        "/bin/sleep 300",
    ]);
    let pane = tmux.start_reader(
        &["new-window", "-d", "-t", "work:", "-n", "target"],
        "reader",
    );
    let bridge = Bridge::start_with_input(&tmux);
    let target = pane_target(&bridge.workspace(), "target");
    let wid = target["windowId"].as_str().unwrap();
    let focus = || tmux.run(&["display-message", "-p", "-t", "work:", "#{window_name}"]);
    assert_eq!(focus(), "focus");
    let change_focus = format!("select-window -t 'work:{wid}'");
    for options in [
        vec!["-g"],
        vec!["-t", "work"],
        vec!["-w", "-t", wid],
        vec!["-p", "-t", &pane],
    ] {
        let hook = "after-send-keys[77]";
        let mut set = vec!["set-hook"];
        set.extend(&options);
        set.extend([hook, &change_focus]);
        tmux.run(&set);
        let response = bridge.send_input(&target, json!({"key": "enter"}), "");
        assert_eq!(focus(), "focus", "POST ran {hook} at {options:?}");
        assert_eq!(response, (409, String::new()), "{options:?}");
        let mut unset = vec!["set-hook", "-u"];
        unset.extend(options);
        unset.push(hook);
        tmux.run(&unset);
    }
    tmux.assert_reader_received_only_probe(&target, "reader");
}

#[test]
fn pane_input_ignores_the_attached_read_only_tmux_client() {
    let tmux = Tmux::new();
    tmux.run(&[
        "new-session",
        "-d",
        "-s",
        "work",
        "-n",
        "focus",
        "/bin/sleep 300",
    ]);
    tmux.start_reader(
        &["new-window", "-d", "-t", "work:", "-n", "target"],
        "reader",
    );
    let bridge = Bridge::start_with_input(&tmux);
    let target = pane_target(&bridge.workspace(), "target");
    // Control mode provides an attached client without requiring a test-side PTY.
    let mut client = tmux
        .tmux()
        .args(["-C", "attach-session", "-r", "-t", "work"])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .spawn()
        .unwrap();
    let deadline = Instant::now() + TIMEOUT;
    while tmux.run(&["list-clients", "-F", "#{client_readonly}"]) != "1" {
        assert!(
            Instant::now() < deadline,
            "read-only tmux client did not attach"
        );
        thread::sleep(Duration::from_millis(10));
    }
    let hooks = ["command-error", "after-display-message"];
    for hook in hooks {
        tmux.run(&[
            "set-hook",
            "-g",
            hook,
            &format!("set-option -g @{hook} hit"),
        ]);
    }

    let text = "-β🙂;";
    let text_response = bridge.send_input(&target, json!({"text": text}), "");
    let key_response = bridge.send_input(&target, json!({"key": "enter"}), "");
    // Closing control stdin detaches the client after both requests have completed.
    assert!(client.wait().unwrap().success());
    for hook in hooks {
        assert_eq!(
            tmux.run(&["show-options", "-gqv", &format!("@{hook}")]),
            "",
            "POST ran {hook}"
        );
    }
    assert_eq!(text_response, (204, String::new()));
    assert_eq!(key_response, (204, String::new()));
    tmux.assert_reader_bytes("reader", format!("{text}\r").as_bytes());
    assert_eq!(
        tmux.run(&[
            "list-windows",
            "-t",
            "work",
            "-f",
            "#{window_active}",
            "-F",
            "#{window_name}"
        ]),
        "focus"
    );
}

#[test]
fn pane_input_deadline_is_uncertain_without_replaying_a_delivered_write() {
    let tmux = Tmux::new();
    tmux.start_reader(
        &["new-session", "-d", "-s", "work", "-n", "target"],
        "reader",
    );
    let bridge = Bridge::start_with_tmux_wrapper(
        &tmux,
        &[ENABLE_PANE_INPUT],
        r#"#!/bin/sh
PATH=${PATH#*:}; export PATH
case "$*" in
    *"source-file -")
        if [ ! -e input-attempts ]; then
            echo first >> input-attempts
            tmux "$@" >/dev/null || exit $?
            : > delivered
            exec /bin/sleep 15
        fi
        echo next >> input-attempts
        ;;
esac
exec tmux "$@"
"#,
    );
    let target = pane_target(&bridge.workspace(), "target");
    assert_eq!(
        bridge.send_input(&target, json!({"text": "first"}), ""),
        (503, String::new())
    );
    assert!(
        tmux.dir.join("delivered").exists(),
        "wrapper did not complete the tmux command"
    );
    tmux.assert_reader_bytes("reader", b"first");
    assert_eq!(bridge.read(&target, "").0, 200);
    let attempts = || fs::read_to_string(tmux.dir.join("input-attempts")).unwrap();
    assert_eq!(
        attempts(),
        "first\n",
        "bridge retried an uncertain mutation"
    );

    // This is a fresh action after reconciliation, not a replay of the uncertain input.
    assert_eq!(
        bridge.send_input(&target, json!({"text": "next"}), ""),
        (204, String::new())
    );
    tmux.assert_reader_bytes("reader", b"firstnext");
    assert_eq!(attempts(), "first\nnext\n");
}

fn window_named<'a>(workspace: &'a Value, name: &str) -> &'a Value {
    workspace["session"]["windows"]
        .as_array()
        .unwrap()
        .iter()
        .find(|window| window["name"] == name)
        .expect("listed window")
}

fn pane_target(workspace: &Value, name: &str) -> Value {
    let mut target = open_target(workspace);
    let window = window_named(workspace, name);
    target["windowId"] = window["id"].clone();
    target["paneId"] = json!(
        window["paneId"]
            .as_str()
            .expect("single-pane ID in inventory")
    );
    target
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

    fn start_reader(&self, args: &[&str], output: &str) -> String {
        let mut args = args.to_vec();
        args.extend([
            "-P",
            "-F",
            "#{pane_id}",
            "-c",
            self.dir.to_str().unwrap(),
            "/bin/sh",
            "-c",
            r#"stty raw -echo && printf 'reader-ready\n' && exec /bin/cat > "$1""#,
            "glowkin-reader",
            output,
        ]);
        let pane = self.run(&args);
        self.wait_for_screen(&pane, "reader-ready");
        pane
    }

    fn assert_reader_bytes(&self, output: &str, expected: &[u8]) {
        let deadline = Instant::now() + TIMEOUT;
        loop {
            let bytes = fs::read(self.dir.join(output)).unwrap_or_default();
            assert!(
                expected.starts_with(&bytes),
                "{output}: expected {expected:?}, received {bytes:?}"
            );
            if bytes == expected {
                return;
            }
            assert!(
                Instant::now() < deadline,
                "{output}: expected {expected:?}, received {bytes:?}"
            );
            thread::sleep(Duration::from_millis(10));
        }
    }

    fn assert_reader_received_only_probe(&self, target: &Value, output: &str) {
        // A direct write proves the reader is live and follows any earlier pane input in order.
        self.send_reader_probe(target);
        self.assert_reader_bytes(output, b"probe");
    }

    fn send_reader_probe(&self, target: &Value) {
        let pane = target["paneId"].as_str().unwrap();
        self.run(&["send-keys", "-l", "-t", pane, "probe"]);
    }

    fn wait_for_screen(&self, pane: &str, marker: &str) {
        let deadline = Instant::now() + TIMEOUT;
        while !self
            .run(&["capture-pane", "-p", "-t", pane])
            .contains(marker)
        {
            assert!(Instant::now() < deadline, "pane did not paint {marker}");
            thread::sleep(Duration::from_millis(10));
        }
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
        Self::start_command(tmux.command(env!("CARGO_BIN_EXE_glowkin-bridge")))
    }

    fn start_with_input(tmux: &Tmux) -> Self {
        let mut command = tmux.command(env!("CARGO_BIN_EXE_glowkin-bridge"));
        command.arg(ENABLE_PANE_INPUT);
        Self::start_command(command)
    }

    fn start_with_tmux_wrapper(tmux: &Tmux, args: &[&str], script: &str) -> Self {
        let wrapper = tmux.dir.join("tmux");
        fs::write(&wrapper, script).unwrap();
        fs::set_permissions(&wrapper, fs::Permissions::from_mode(0o700)).unwrap();
        let path = std::env::join_paths(
            std::iter::once(tmux.dir.clone())
                .chain(std::env::split_paths(&std::env::var_os("PATH").unwrap())),
        )
        .unwrap();
        let mut command = tmux.command(env!("CARGO_BIN_EXE_glowkin-bridge"));
        command.args(args).env("PATH", path);
        Self::start_command(command)
    }

    fn start_command(mut command: Command) -> Self {
        let reservation = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = reservation.local_addr().unwrap();
        drop(reservation);
        let child = command
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

    fn read(&self, target: &Value, headers: &str) -> (u16, String) {
        let body = target.to_string();
        self.request(
            "POST",
            READ_PANE,
            &format!(
                "Content-Type: application/json\r\nContent-Length: {}\r\n{headers}",
                body.len()
            ),
            &body,
        )
    }

    fn send_input(&self, target: &Value, input: Value, headers: &str) -> (u16, String) {
        let mut body = target.clone();
        body["input"] = input;
        let body = body.to_string();
        self.request(
            "POST",
            SEND_INPUT,
            &format!(
                "Content-Type: application/json\r\nContent-Length: {}\r\n{headers}",
                body.len()
            ),
            &body,
        )
    }

    fn request(&self, method: &str, path: &str, headers: &str, body: &str) -> (u16, String) {
        let (status, _, body) = self.raw_request(method, path, headers, body);
        (status, body)
    }

    fn raw_request(
        &self,
        method: &str,
        path: &str,
        headers: &str,
        body: &str,
    ) -> (u16, String, String) {
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
            format!("{head}\r\n"),
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
