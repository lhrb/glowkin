use std::{
    collections::HashMap, ffi::OsStr, fmt::Write, io, os::unix::ffi::OsStrExt, process::Stdio,
    time::Duration,
};

use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    process::Command,
    sync::Semaphore,
    time::timeout,
};

use super::{
    Folder, InputError, OpenError, PaneInput, PaneInputCommand, PaneKey, PaneTarget, PiChatError,
    ReadError, Session, Target, Window, Workspace,
};

const DEADLINE: Duration = Duration::from_secs(2);
const OUTPUT_LIMIT: usize = 64 * 1024;
// tmux executes after-capture-pane even with -p. Refuse hooks rather than
// running arbitrary PC actions or temporarily changing tmux configuration.
const PANE_READ_ELIGIBLE: &str = "#{&&:#{==:#{window_panes},1},#{==:#{after-capture-pane},}}";
const FOLDER_FIELD: &str =
    "#{?#{&&:#{==:#{window_panes},1},#{==:#{pane_dead},0}},#{q:pane_current_path},}\t";

pub(crate) struct Tmux {
    slots: Semaphore,
}

impl Tmux {
    pub(crate) fn new() -> Self {
        Self {
            slots: Semaphore::new(4),
        }
    }

    pub(crate) async fn inventory(&self) -> Workspace {
        if let Ok(out) = self.list_windows(true, true).await
            && out.status.success()
            && let Some(workspace) = parse_inventory_with_folders(&out.stdout)
        {
            return workspace;
        }
        // Folder failures (including output overflow) must not cost pane IDs.
        let (out, has_panes) = match self.list_windows(true, false).await {
            Err(error) if error.kind() == io::ErrorKind::FileTooLarge => {
                // Optional pane IDs must not displace a previously listable inventory.
                (self.list_windows(false, false).await, false)
            }
            out => (out, true),
        };
        match out {
            Ok(out) if out.status.success() => {
                parse_inventory(&out.stdout, has_panes).unwrap_or(Workspace::Unavailable)
            }
            _ => Workspace::Unavailable,
        }
    }

    async fn list_windows(
        &self,
        has_panes: bool,
        with_folders: bool,
    ) -> io::Result<std::process::Output> {
        let folder_field = if with_folders { FOLDER_FIELD } else { "" };
        let pane_field = if has_panes {
            format!("#{{?{PANE_READ_ELIGIBLE},#{{pane_id}},}}\t")
        } else {
            String::new()
        };
        let command = format!(
            "list-windows -a -F '{folder_field}#{{pid}}\t#{{start_time}}\t#{{session_id}}\t#{{window_id}}\t{pane_field}#{{session_name}}\t#{{window_name}}'"
        );
        self.run(&[
            "if-shell",
            "-F",
            "#{==:#{server_sessions},1}",
            &command,
            "display-message -p 'count #{server_sessions}'",
        ])
        .await
    }

    pub(crate) async fn open_shell(&self, target: Target) -> Result<(), OpenError> {
        let home = std::env::home_dir()
            .filter(|path| path.is_absolute())
            .ok_or(OpenError::Unavailable)?;
        let id = format!("${}", target.session_id);
        let exact = format!("{id}:");
        // if-shell -F inserts the branch into the same command queue without yielding.
        let guard = format!(
            "#{{&&:#{{==:#{{server_sessions}},1}},#{{&&:#{{==:#{{session_id}},{id}}},#{{==:#{{pid}}-#{{start_time}},{}-{}}}}}}}",
            target.server_pid, target.server_start,
        );
        // tmux supplies SHELL from default-shell. Use multiple argv entries so tmux
        // executes the fixed launcher directly instead of interpreting it with that shell.
        let open = format!(
            "new-window -d -P -F opened -t '{exact}' /bin/sh -c 'HOME=\"$1\"; export HOME; cd -P -- \"$HOME\" || exit; exec \"$SHELL\" -i' glowkin {}",
            quote_tmux_argument(home.as_os_str()),
        );
        let out = self
            .run(&[
                "if-shell",
                "-F",
                "-t",
                &exact,
                &guard,
                &open,
                "display-message -p stale",
            ])
            .await
            .map_err(|error| match error.kind() {
                io::ErrorKind::NotFound | io::ErrorKind::PermissionDenied => OpenError::Unavailable,
                _ => OpenError::Uncertain,
            })?;
        if out.status.success() {
            return match out.stdout.as_slice() {
                b"opened\n" => Ok(()),
                b"stale\n" => Err(OpenError::Unavailable),
                _ => Err(OpenError::Uncertain),
            };
        }
        let error = String::from_utf8_lossy(&out.stderr);
        if error.trim_end() == format!("can't find session: {id}") || no_server(&error) {
            Err(OpenError::Unavailable)
        } else {
            Err(OpenError::Uncertain)
        }
    }

    pub(crate) async fn read_pane(&self, target: PaneTarget) -> Result<String, ReadError> {
        let (window, pane, guard) = pane_read_selection(&target);
        // The branch runs in the same queue without yielding. -p prints the
        // visible screen only (no history, attributes, or tmux paste buffer).
        let capture = format!("capture-pane -p -t '{pane}'");
        let out = self
            .run(&["if-shell", "-F", "-t", &window, &guard, &capture])
            .await
            .map_err(|_| ReadError::CaptureFailed)?;
        // An unselected branch prints nothing; capture-pane -p emits at least a
        // newline even when the screen is blank.
        if !out.status.success() || out.stdout.is_empty() {
            return Err(ReadError::TargetUnavailable);
        }
        String::from_utf8(out.stdout).map_err(|_| ReadError::CaptureFailed)
    }

    pub(super) async fn pi_chat_marker(
        &self,
        target: &PaneTarget,
    ) -> Result<Option<String>, PiChatError> {
        let (window, pane, guard) = pane_read_selection(target);
        // Run the guard and the pane-local option read synchronously in one queue.
        // Without -A, show-options -p cannot return an inherited window option.
        // Do not fire after-show-options hooks. list-commands has no such hook.
        let script = format!(
            "if-shell -F -t '{window}' '{guard}' {{\n\
                if-shell -F -t '{window}' '#{{==:#{{after-show-options}},}}' {{\n\
                    list-commands -F eligible show-options\n\
                    show-options -pqv -t '{pane}' @glowkin-chat\n\
                }}\n\
            }} {{ list-commands -F stale show-options }}\n"
        );
        let output = self
            .run_tmux(&["source-file", "-"], Some(script.as_bytes()))
            .await
            .map_err(|_| PiChatError::Uncertain)?;
        parse_pi_chat_marker(output)
    }

    pub(crate) async fn send_input(&self, command: PaneInputCommand) -> Result<(), InputError> {
        let PaneInputCommand { target, input } = command;
        let session = target.session;
        let sid = format!("${}", session.session_id);
        let wid = format!("@{}", target.window_id);
        let pane = format!("%{}", target.pane_id);
        // if-shell can fall back to its client context when -t cannot resolve.
        // Compare every ID and require explicit zeroes (missing flags expand to empty).
        let checks = [
            "#{==:#{server_sessions},1}".to_owned(),
            format!(
                "#{{==:#{{pid}}-#{{start_time}},{}-{}}}",
                session.server_pid, session.server_start
            ),
            format!("#{{==:#{{session_id}},{sid}}}"),
            format!("#{{==:#{{window_id}},{wid}}}"),
            "#{==:#{window_panes},1}".to_owned(),
            format!("#{{==:#{{pane_id}},{pane}}}"),
            "#{==:#{pane_in_mode},0}".to_owned(),
            "#{==:#{pane_dead},0}".to_owned(),
            "#{==:#{pane_input_off},0}".to_owned(),
            "#{==:#{after-send-keys},}".to_owned(),
        ];
        let guard = checks
            .into_iter()
            .reduce(|left, right| format!("#{{&&:{left},{right}}}"))
            .unwrap();
        // An explicit empty client avoids borrowing an attached PC client.
        // A read-only attached client would otherwise fail and fire command-error.
        let send = match input {
            PaneInput::Text(text) => format!(
                "send-keys -c '' -l -t {pane} -- {}",
                quote_tmux_argument(OsStr::new(text.as_str()))
            ),
            PaneInput::Key(key) => format!("send-keys -c '' -t {pane} {}", input_key(key)),
        };
        // source-file reads stdin before running the guard. if-shell -F runs the
        // parsed command block in the same queue, without yielding or reparsing text.
        // list-commands has no configurable after hook, unlike display-message.
        let script = format!(
            "if-shell -F -t '{sid}:{wid}' \"{guard}\" {{ {send}; list-commands -F sent send-keys }} {{ list-commands -F rejected send-keys }}\n"
        );
        let out = self
            .run_tmux(&["source-file", "-"], Some(script.as_bytes()))
            .await
            .map_err(|error| match error.kind() {
                io::ErrorKind::NotFound | io::ErrorKind::PermissionDenied => InputError::Rejected,
                _ => InputError::Uncertain,
            })?;
        match (out.status.success(), out.stdout.as_slice()) {
            (true, b"sent\n") => Ok(()),
            (true, b"rejected\n") => Err(InputError::Rejected),
            (false, b"") if no_server(&String::from_utf8_lossy(&out.stderr)) => {
                Err(InputError::Rejected)
            }
            _ => Err(InputError::Uncertain),
        }
    }

    async fn run(&self, args: &[&str]) -> io::Result<std::process::Output> {
        self.run_tmux(args, None).await
    }

    async fn run_tmux(
        &self,
        args: &[&str],
        stdin: Option<&[u8]>,
    ) -> io::Result<std::process::Output> {
        let _slot = self.slots.try_acquire().map_err(io::Error::other)?;
        timeout(DEADLINE, async {
            let mut child = Command::new("tmux")
                .args(["-N", "-u"]) // Never start a server; force UTF-8 with the C locale.
                .args(args)
                .env_remove("TMUX")
                .env_remove("TMUX_PANE")
                .env("LC_ALL", "C")
                .stdin(if stdin.is_some() {
                    Stdio::piped()
                } else {
                    Stdio::null()
                })
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .kill_on_drop(true)
                .spawn()?;
            let stdout = child.stdout.take().unwrap();
            let stderr = child.stderr.take().unwrap();
            let pipe = child.stdin.take();
            let (status, stdout, stderr, written) = tokio::try_join!(
                child.wait(),
                read_bounded(stdout, io::ErrorKind::FileTooLarge),
                read_bounded(stderr, io::ErrorKind::Other),
                async move {
                    let written = match (pipe, stdin) {
                        (Some(mut pipe), Some(input)) => pipe.write_all(input).await,
                        _ => Ok(()),
                    };
                    // Keep tmux's connection error if the client closes stdin early.
                    Ok(written)
                },
            )?;
            if status.success() {
                written?;
            }
            Ok(std::process::Output {
                status,
                stdout,
                stderr,
            })
        })
        .await?
    }
}

fn pane_read_selection(target: &PaneTarget) -> (String, String, String) {
    let session = &target.session;
    let sid = format!("${}", session.session_id);
    let wid = format!("@{}", target.window_id);
    let pane = format!("%{}", target.pane_id);
    // Resolve the window in its session, not by a global %pane lookup. In a
    // single-pane window the format's pane is the only possible exact match.
    let checks = [
        "#{==:#{server_sessions},1}".to_owned(),
        format!(
            "#{{==:#{{pid}}-#{{start_time}},{}-{}}}",
            session.server_pid, session.server_start
        ),
        format!("#{{==:#{{session_id}},{sid}}}"),
        format!("#{{==:#{{window_id}},{wid}}}"),
        PANE_READ_ELIGIBLE.to_owned(),
        format!("#{{==:#{{pane_id}},{pane}}}"),
    ];
    let guard = checks
        .into_iter()
        .reduce(|left, right| format!("#{{&&:{left},{right}}}"))
        .unwrap();
    (format!("{sid}:{wid}"), pane, guard)
}

fn parse_pi_chat_marker(output: std::process::Output) -> Result<Option<String>, PiChatError> {
    if !output.status.success() {
        let error = String::from_utf8_lossy(&output.stderr);
        if no_server(&error) && !error.trim_end().ends_with(" (Permission denied)") {
            return Err(PiChatError::Conflict);
        }
        return Err(PiChatError::Uncertain);
    }
    if !output.stderr.is_empty() {
        return Err(PiChatError::Uncertain);
    }
    match output.stdout.as_slice() {
        b"stale\n" => Err(PiChatError::Conflict),
        b"eligible\n" => Ok(None),
        bytes => {
            let marker = bytes
                .strip_prefix(b"eligible\n")
                .and_then(|value| value.strip_suffix(b"\n"))
                .ok_or(PiChatError::Uncertain)?;
            std::str::from_utf8(marker)
                .map(|marker| Some(marker.to_owned()))
                .map_err(|_| PiChatError::Uncertain)
        }
    }
}

fn no_server(error: &str) -> bool {
    error.starts_with("no server running on ")
        || (error.starts_with("error connecting to ")
            && (error.trim_end().ends_with(" (No such file or directory)")
                || error.trim_end().ends_with(" (Connection refused)")
                || error.trim_end().ends_with(" (Permission denied)")))
}

fn input_key(key: PaneKey) -> &'static str {
    match key {
        PaneKey::Enter => "Enter",
        PaneKey::Escape => "Escape",
        PaneKey::Up => "Up",
        PaneKey::Down => "Down",
        PaneKey::Left => "Left",
        PaneKey::Right => "Right",
        PaneKey::Backspace => "BSpace",
        PaneKey::CtrlO => "C-o",
    }
}

async fn read_bounded(
    reader: impl AsyncReadExt + Unpin,
    overflow: io::ErrorKind,
) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    reader
        .take((OUTPUT_LIMIT + 1) as u64)
        .read_to_end(&mut bytes)
        .await?;
    if bytes.len() > OUTPUT_LIMIT {
        return Err(io::Error::new(overflow, "tmux output exceeded limit"));
    }
    Ok(bytes)
}

fn parse_inventory(bytes: &[u8], has_panes: bool) -> Option<Workspace> {
    let text = String::from_utf8_lossy(bytes);
    if let Some(count) = text.strip_prefix("count ") {
        return match count.trim_end().parse::<u32>().ok()? {
            0 => Some(Workspace::NoSession),
            2.. => Some(Workspace::MultipleSessions),
            _ => None,
        };
    }
    // tmux rejects control characters in session and window names.
    let columns = if has_panes { 7 } else { 6 };
    let mut rows = text.lines().map(|row| row.splitn(columns, '\t'));
    let mut first = rows.next()?;
    let pid = first.next()?;
    let start = first.next()?;
    let session_id = first.next()?;
    let mut windows = Vec::new();
    let mut push_window = |mut row: std::str::SplitN<'_, char>| {
        let id = row.next()?;
        id.strip_prefix('@')?.parse::<u32>().ok()?;
        let pane = if has_panes { row.next()? } else { "" };
        let pane_id = if pane.is_empty() {
            None
        } else {
            pane.strip_prefix('%')?.parse::<u32>().ok()?;
            Some(pane.to_owned())
        };
        let session_name = row.next()?;
        windows.push(Window {
            id: id.to_owned(),
            name: row.next()?.to_owned(),
            pane_id,
            folder: None,
        });
        Some(session_name.to_owned())
    };
    let name = push_window(first)?;
    for mut row in rows {
        if row.next()? != pid
            || row.next()? != start
            || row.next()? != session_id
            || push_window(row)? != name
        {
            return None;
        }
    }
    Some(Workspace::Ready(Session {
        target: Target {
            session_id: session_id.strip_prefix('$')?.parse().ok()?,
            server_pid: pid.parse().ok()?,
            server_start: start.parse().ok()?,
        },
        name,
        windows,
    }))
}

fn parse_inventory_with_folders(mut bytes: &[u8]) -> Option<Workspace> {
    if bytes.starts_with(b"count ") {
        return parse_inventory(bytes, true);
    }
    let mut inventory = Vec::new();
    let mut folders = Vec::new();
    let mut keys = HashMap::new();
    while !bytes.is_empty() {
        let (path, row) = read_path_field(bytes)?;
        let end = row.iter().position(|&byte| byte == b'\n')? + 1;
        inventory.extend_from_slice(&row[..end]);
        bytes = &row[end..];

        folders.push(folder_name(&path).map(str::to_owned).map(|name| {
            let next_key = keys.len();
            let key = *keys.entry(path).or_insert(next_key);
            Folder {
                key: key.to_string(),
                name,
            }
        }));
    }
    let Workspace::Ready(mut session) = parse_inventory(&inventory, true)? else {
        return None;
    };
    for (window, folder) in session.windows.iter_mut().zip(folders) {
        window.folder = folder;
    }
    Some(Workspace::Ready(session))
}

fn read_path_field(mut bytes: &[u8]) -> Option<(Vec<u8>, &[u8])> {
    // tmux's q modifier prefixes shell specials (including backslash, LF and
    // tab) with a backslash, leaving other bytes intact. Decode before UTF-8
    // conversion and treat only unescaped tabs as delimiters.
    let mut path = Vec::new();
    while let Some((&byte, rest)) = bytes.split_first() {
        bytes = rest;
        match byte {
            b'\t' => return Some((path, bytes)),
            b'\n' => return None,
            b'\\' => {
                let (&escaped, rest) = bytes.split_first()?;
                if !b"|&;<>(){}$`\\\"'*?[# =%\n\t".contains(&escaped) {
                    return None;
                }
                path.push(escaped);
                bytes = rest;
            }
            _ => path.push(byte),
        }
    }
    None
}

fn folder_name(path: &[u8]) -> Option<&str> {
    // Linux tmux reads at most PATH_MAX bytes from procfs. A path at that
    // limit may be truncated. A deleted cwd has an ambiguous kernel suffix.
    if !path.starts_with(b"/") || path.len() >= 4096 || path.ends_with(b" (deleted)") {
        return None;
    }
    let basename = if path == b"/" {
        path
    } else {
        path.rsplit(|&byte| byte == b'/').next()?
    };
    let name = std::str::from_utf8(basename).ok()?;
    (!name.trim().is_empty()
        && !name
            .chars()
            .any(|ch| ch.is_control() || matches!(ch, '\u{2028}' | '\u{2029}')))
    .then_some(name)
}

fn quote_tmux_argument(arg: &OsStr) -> String {
    // Octal escapes in a tmux double-quoted token decode to literal bytes, not syntax.
    let mut quoted = String::from("\"");
    for byte in arg.as_bytes() {
        write!(&mut quoted, "\\{byte:03o}").unwrap();
    }
    quoted.push('"');
    quoted
}

#[cfg(test)]
mod tests {
    use std::{
        os::unix::process::ExitStatusExt,
        process::{ExitStatus, Output},
    };

    use super::*;

    #[test]
    fn requires_proof_of_exact_eligibility_before_reporting_marker_absence() {
        let parse = |stdout: &[u8]| {
            parse_pi_chat_marker(Output {
                status: ExitStatus::from_raw(0),
                stdout: stdout.to_vec(),
                stderr: vec![],
            })
        };
        assert_eq!(parse(b"eligible\n"), Ok(None));
        assert_eq!(parse(b"eligible\n\n"), Ok(Some(String::new())));
        assert_eq!(
            parse(b"eligible\nactive:123\n"),
            Ok(Some("active:123".into()))
        );
        assert_eq!(parse(b"stale\n"), Err(PiChatError::Conflict));
        assert_eq!(parse(b""), Err(PiChatError::Uncertain));
        assert_eq!(parse(b"eligible\nactive:123"), Err(PiChatError::Uncertain));
        assert_eq!(parse(b"eligible\n\xff\n"), Err(PiChatError::Uncertain));
    }
}
