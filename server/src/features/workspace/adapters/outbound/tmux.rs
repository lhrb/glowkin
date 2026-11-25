use std::{ffi::OsStr, fmt::Write, io, os::unix::ffi::OsStrExt, process::Stdio, time::Duration};

use tokio::{io::AsyncReadExt, process::Command, sync::Semaphore, time::timeout};

use super::{OpenError, PaneTarget, ReadError, Session, Target, Window, Workspace};

const DEADLINE: Duration = Duration::from_secs(2);
const OUTPUT_LIMIT: usize = 64 * 1024;
// tmux executes after-capture-pane even with -p. Refuse hooks rather than
// running arbitrary PC actions or temporarily changing tmux configuration.
const PANE_READ_ELIGIBLE: &str = "#{&&:#{==:#{window_panes},1},#{==:#{after-capture-pane},}}";

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
        let (out, has_panes) = match self.list_windows(true).await {
            Err(error) if error.kind() == io::ErrorKind::FileTooLarge => {
                // Optional pane IDs must not displace a previously listable inventory.
                (self.list_windows(false).await, false)
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

    async fn list_windows(&self, has_panes: bool) -> io::Result<std::process::Output> {
        let pane_field = if has_panes {
            format!("#{{?{PANE_READ_ELIGIBLE},#{{pane_id}},}}\t")
        } else {
            String::new()
        };
        let command = format!(
            "list-windows -a -F '#{{pid}}\t#{{start_time}}\t#{{session_id}}\t#{{window_id}}\t{pane_field}#{{session_name}}\t#{{window_name}}'"
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
        if error.trim_end() == format!("can't find session: {id}")
            || error.starts_with("no server running on ")
            || (error.starts_with("error connecting to ")
                && (error.trim_end().ends_with(" (No such file or directory)")
                    || error.trim_end().ends_with(" (Connection refused)")
                    || error.trim_end().ends_with(" (Permission denied)")))
        {
            Err(OpenError::Unavailable)
        } else {
            Err(OpenError::Uncertain)
        }
    }

    pub(crate) async fn read_pane(&self, target: PaneTarget) -> Result<String, ReadError> {
        let session = target.session;
        let sid = format!("${}", session.session_id);
        let wid = format!("@{}", target.window_id);
        let pane = format!("%{}", target.pane_id);
        let window = format!("{sid}:{wid}");
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

    async fn run(&self, args: &[&str]) -> io::Result<std::process::Output> {
        let _slot = self.slots.try_acquire().map_err(io::Error::other)?;
        timeout(DEADLINE, async {
            let mut child = Command::new("tmux")
                .args(["-N", "-u"]) // Never start a server; force UTF-8 with the C locale.
                .args(args)
                .env_remove("TMUX")
                .env_remove("TMUX_PANE")
                .env("LC_ALL", "C")
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .kill_on_drop(true)
                .spawn()?;
            let stdout = child.stdout.take().unwrap();
            let stderr = child.stderr.take().unwrap();
            let (status, stdout, stderr) = tokio::try_join!(
                child.wait(),
                read_bounded(stdout, io::ErrorKind::FileTooLarge),
                read_bounded(stderr, io::ErrorKind::Other),
            )?;
            Ok(std::process::Output {
                status,
                stdout,
                stderr,
            })
        })
        .await?
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

fn quote_tmux_argument(arg: &OsStr) -> String {
    // Octal escapes in a tmux double-quoted token decode to literal bytes, not syntax.
    let mut quoted = String::from("\"");
    for byte in arg.as_bytes() {
        write!(&mut quoted, "\\{byte:03o}").unwrap();
    }
    quoted.push('"');
    quoted
}
