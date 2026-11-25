#[path = "adapters/outbound/tmux.rs"]
mod tmux;

pub(crate) use tmux::Tmux;

pub(crate) enum Workspace {
    Ready(Session),
    NoSession,
    MultipleSessions,
    Unavailable,
}

pub(crate) struct Session {
    pub target: Target,
    pub name: String,
    pub windows: Vec<Window>,
}

pub(crate) struct Window {
    pub id: String,
    pub name: String,
    pub pane_id: Option<String>,
}

pub(crate) struct Target {
    pub session_id: u32,
    pub server_pid: u32,
    pub server_start: u64,
}

pub(crate) struct PaneTarget {
    pub session: Target,
    pub window_id: u32,
    pub pane_id: u32,
}

pub(crate) enum ReadError {
    TargetUnavailable,
    CaptureFailed,
}

pub(crate) enum OpenError {
    Unavailable,
    Uncertain,
}
