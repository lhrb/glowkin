use std::collections::HashMap;
use std::fmt::Write as _;
use std::fs::Metadata;
use std::io;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const OPTION: &str = "@glowkin-pi-signals";
const RENEW: Duration = Duration::from_secs(2);
const LEASE_SECONDS: u64 = 6;

pub(crate) struct Chooser {
    socket: PathBuf,
    generation: String,
    next_server_check: Option<Instant>,
    states: HashMap<String, &'static str>,
    next_refresh: Instant,
    failed: bool,
}

impl Chooser {
    pub(crate) fn start(socket: &Path, expected: Option<&str>) -> io::Result<Self> {
        if !socket.is_absolute() {
            return Err(io::Error::other("expected an absolute tmux socket path"));
        }
        let generation = server_generation(socket)?;
        if expected.is_some_and(|expected| expected != generation) {
            return Err(io::Error::other("the tmux server has changed"));
        }
        Ok(Self {
            socket: socket.into(),
            generation,
            next_server_check: expected.map(|_| Instant::now()),
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

    pub(crate) fn is_current(&self) -> bool {
        server_generation(&self.socket).is_ok_and(|value| value == self.generation)
    }

    pub(crate) fn advertise(&self, path: &Path, endpoint: &Metadata) -> io::Result<()> {
        // Encode the pathname as data, not as part of tmux's command syntax.
        let mut encoded = String::new();
        for byte in path.as_os_str().as_encoded_bytes() {
            write!(encoded, "{byte:02x}").expect("writing to a String cannot fail");
        }
        // Identify the actual entry too, so a different listener at the same
        // path cannot inherit a stopped receiver's registration.
        let changed =
            i128::from(endpoint.ctime()) * 1_000_000_000 + i128::from(endpoint.ctime_nsec());
        self.update(&format!(
            "set-option -s @glowkin-pi-socket {encoded}:{}:{}:{changed}",
            endpoint.dev(),
            endpoint.ino()
        ))
    }

    pub(crate) fn wait(&mut self) -> io::Result<Option<Duration>> {
        if self
            .next_server_check
            .is_some_and(|at| Instant::now() >= at)
        {
            if !self.is_current() {
                return Err(io::ErrorKind::NotConnected.into());
            }
            self.next_server_check = Some(Instant::now() + RENEW);
        }
        let refresh = !self.states.is_empty() || self.failed;
        if refresh && Instant::now() >= self.next_refresh {
            self.refresh();
        }
        Ok(self
            .next_server_check
            .into_iter()
            .chain(refresh.then_some(self.next_refresh))
            .min()
            .map(|at| at.saturating_duration_since(Instant::now())))
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
        self.update(&command)
    }

    fn update(&self, command: &str) -> io::Result<()> {
        // The same socket path could later belong to a server with different panes.
        let same_server = format!("#{{==:#{{pid}}:#{{start_time}},{}}}", self.generation);
        let output = tmux(&self.socket)
            .args(["if-shell", "-F", &same_server, command])
            .output()?;
        if !output.status.success() {
            return Err(io::Error::other(
                String::from_utf8_lossy(&output.stderr).trim(),
            ));
        }
        Ok(())
    }
}

fn server_generation(socket: &Path) -> io::Result<String> {
    let output = tmux(socket)
        .args(["display-message", "-p", "#{pid}:#{start_time}"])
        .output()?;
    let generation = String::from_utf8_lossy(&output.stdout);
    let generation = generation.trim();
    if output.status.success()
        && generation
            .split_once(':')
            .is_some_and(|(pid, start)| pid.parse::<u32>().is_ok() && start.parse::<u64>().is_ok())
    {
        Ok(generation.into())
    } else {
        Err(io::Error::other("could not identify the tmux server"))
    }
}

fn tmux(socket: &Path) -> Command {
    let mut command = Command::new("tmux");
    command.args(["-N", "-S"]).arg(socket);
    command
}
