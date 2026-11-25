use std::collections::HashMap;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const OPTION: &str = "@glowkin-pi-signals";
const RENEW: Duration = Duration::from_secs(2);
const LEASE_SECONDS: u64 = 6;

pub(crate) struct Chooser {
    socket: PathBuf,
    server_pid: u32,
    states: HashMap<String, &'static str>,
    next_refresh: Instant,
    failed: bool,
}

impl Chooser {
    pub(crate) fn start(socket: &Path) -> io::Result<Self> {
        if !socket.is_absolute() {
            return Err(io::Error::other("expected an absolute tmux socket path"));
        }
        let output = tmux(socket)
            .args(["display-message", "-p", "#{pid}"])
            .output()?;
        let server_pid = String::from_utf8_lossy(&output.stdout)
            .trim()
            .parse()
            .ok()
            .filter(|_| output.status.success())
            .ok_or_else(|| io::Error::other("could not identify the tmux server"))?;
        Ok(Self {
            socket: socket.into(),
            server_pid,
            states: HashMap::new(),
            next_refresh: Instant::now(),
            failed: false,
        })
    }

    pub(crate) fn observe(&mut self, pane: String, state: &'static str) {
        let state = if state == "needs_permission" {
            "permission"
        } else {
            state
        };
        let changed = if state == "gone" {
            self.states.remove(&pane).is_some()
        } else {
            self.states.insert(pane, state) != Some(state)
        };
        if changed {
            self.refresh();
        }
    }

    pub(crate) fn wait(&mut self) -> Option<Duration> {
        if self.states.is_empty() && !self.failed {
            return None;
        }
        if Instant::now() >= self.next_refresh {
            self.refresh();
        }
        Some(self.next_refresh.saturating_duration_since(Instant::now()))
    }

    pub(crate) fn refresh(&mut self) {
        match self.publish() {
            Ok(()) => self.failed = false,
            Err(err) if !self.failed => {
                eprintln!("could not update Pi chooser status: {err}");
                self.failed = true;
            }
            Err(_) => {}
        }
        self.next_refresh = Instant::now() + RENEW;
    }

    fn publish(&self) -> io::Result<()> {
        let command = if self.states.is_empty() {
            format!("set-option -suq {OPTION}")
        } else {
            // The lease hides labels if the receiver stops without seeing disconnects.
            let epoch = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default();
            let mut snapshot = format!("{}|", epoch.as_secs() + LEASE_SECONDS);
            for (pane, state) in &self.states {
                // Pane IDs and state names come from the receiver's validated frames.
                snapshot.push_str(&format!("{pane}:{state}|"));
            }
            format!("set-option -s {OPTION} '{snapshot}'")
        };
        // The same socket path could later belong to a server with different panes.
        let same_server = format!("#{{==:#{{pid}},{}}}", self.server_pid);
        let output = tmux(&self.socket)
            .args(["if-shell", "-F", &same_server, &command])
            .output()?;
        if !output.status.success() {
            return Err(io::Error::other(
                String::from_utf8_lossy(&output.stderr).trim(),
            ));
        }
        Ok(())
    }
}

fn tmux(socket: &Path) -> Command {
    let mut command = Command::new("tmux");
    command.args(["-N", "-S"]).arg(socket);
    command
}
