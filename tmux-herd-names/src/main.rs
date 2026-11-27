use std::env;
use std::ffi::OsString;
use std::process::{Command, ExitCode};

const HERD: &[&str] = &[
    "Pip", "Moss", "Fern", "Wisp", "Clover", "Tansy", "Bramble", "Moth", "Fawn", "Lark", "Finch",
    "Wren", "Otter", "Newt", "Nettle", "Thistle",
];

fn main() -> ExitCode {
    let args: Vec<_> = env::args_os().skip(1).collect();
    match assign(&args) {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(error) => {
            eprintln!("tmux-herd-names: {error}");
            ExitCode::FAILURE
        }
    }
}

fn assign(args: &[OsString]) -> std::io::Result<bool> {
    let [socket, window] = args else {
        return Ok(false);
    };
    let Some(window) = window.to_str().filter(|window| {
        window
            .strip_prefix('@')
            .is_some_and(|id| !id.is_empty() && id.bytes().all(|byte| byte.is_ascii_digit()))
    }) else {
        return Ok(false);
    };
    let tmux = || {
        let mut cmd = Command::new("tmux");
        cmd.arg("-S").arg(socket);
        cmd
    };

    // tmux sets a window-local automatic-rename=off for explicit names.
    // Do not check the inherited option: users may disable auto-rename globally.
    let local = tmux()
        .args(["show-options", "-wqv", "-t", window, "automatic-rename"])
        .output()?;
    if !local.status.success() || !local.stdout.is_empty() {
        return Ok(local.status.success());
    }

    // S/W scan all sessions and windows. Keep selection and rename on tmux's
    // command queue without a shell between them, so concurrent hooks cannot
    // pick the same free name. An empty choice leaves the normal name alone.
    let choice = HERD.iter().rev().fold(String::new(), |rest, name| {
        let taken = format!("#{{S:#{{W:#{{?#{{==:#{{window_name}},{name}}},1,}}}}}}");
        format!("#{{?{taken},{rest},{name}}}")
    });
    let rename = format!("rename-window -t {window} '{choice}'");
    Ok(tmux()
        .args(["if-shell", "-F", "-t", window, &choice, &rename])
        .status()?
        .success())
}
