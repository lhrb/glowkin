mod pi_chat;
#[path = "adapters/outbound/pi_chat_socket.rs"]
mod pi_chat_socket;
#[path = "adapters/outbound/tmux.rs"]
mod tmux;

pub(crate) use pi_chat::{DraftText, PiChatError, PiInstanceId, edit_pi_draft, read_pi_chat};
pub(crate) use pi_chat_socket::{ChatSnapshot, REQUEST_LIMIT as CHAT_REQUEST_LIMIT};
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
    pub folder: Option<Folder>,
}

pub(crate) struct Folder {
    pub key: String,
    pub name: String,
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

pub(crate) struct PaneInputCommand {
    pub target: PaneTarget,
    pub input: PaneInput,
}

pub(crate) enum PaneInput {
    Text(LiteralText),
    Key(PaneKey),
}

pub(crate) struct LiteralText(String);

impl LiteralText {
    pub(crate) fn new(text: String) -> Option<Self> {
        (!text.is_empty()
            && text.len() <= 256
            && !text
                .chars()
                .any(|ch| ch.is_control() || matches!(ch, '\u{2028}' | '\u{2029}')))
        .then_some(Self(text))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

pub(crate) enum PaneKey {
    Enter,
    Escape,
    Up,
    Down,
    Left,
    Right,
    Backspace,
    CtrlO,
}

pub(crate) enum InputError {
    Rejected,
    Uncertain,
}

pub(crate) enum ReadError {
    TargetUnavailable,
    CaptureFailed,
}

pub(crate) enum OpenError {
    Unavailable,
    Uncertain,
}
