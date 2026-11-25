use std::collections::HashMap;
use std::env;
use std::fs;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::mpsc::{self, SyncSender};
use std::thread;

#[derive(Clone, Debug, Eq, PartialEq)]
struct Source {
    pane: String,
    instance: String,
}

fn read_signal(reader: &mut impl BufRead) -> Option<(Source, &'static str)> {
    // Cap the frame before allocating or validating. Invalid streams are closed.
    let mut frame = Vec::new();
    reader.take(64).read_until(b'\n', &mut frame).ok()?;
    let mut fields = str::from_utf8(&frame).ok()?.strip_suffix('\n')?.split('\t');
    let pane = fields.next()?;
    let instance = fields.next()?;
    let state = match fields.next()? {
        "idle" => "idle",
        "busy" => "busy",
        "needs_permission" => "needs_permission",
        "done" => "done",
        _ => return None,
    };
    let digits = pane.strip_prefix('%')?;
    if fields.next().is_some()
        || digits.is_empty()
        || digits.len() > 10
        || !digits.bytes().all(|b| b.is_ascii_digit())
        || instance.len() != 32
        || !instance
            .bytes()
            .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
    {
        return None;
    }
    Some((
        Source {
            pane: pane.into(),
            instance: instance.into(),
        },
        state,
    ))
}

enum Event {
    Signal {
        connection: u64,
        first: bool,
        source: Source,
        state: &'static str,
    },
    Closed {
        connection: u64,
        source: Source,
    },
    ListenerFailed(io::Error),
}

fn read_stream(connection: u64, stream: UnixStream, events: SyncSender<Event>) {
    let mut reader = BufReader::new(stream);
    let mut claimed = None;
    while let Some((source, state)) = read_signal(&mut reader) {
        let first = claimed.is_none();
        if first {
            claimed = Some(source.clone());
        } else if claimed.as_ref() != Some(&source) {
            break;
        }
        if events
            .send(Event::Signal {
                connection,
                first,
                source,
                state,
            })
            .is_err()
        {
            return;
        }
    }
    drop(reader);
    if let Some(source) = claimed {
        let _ = events.send(Event::Closed { connection, source });
    }
}

struct PaneConnection {
    id: u64,
    active: bool,
}

#[derive(Default)]
struct Panes(HashMap<String, PaneConnection>);

impl Panes {
    fn observe(&mut self, event: Event) -> io::Result<Option<(Source, &'static str)>> {
        let output = match event {
            Event::Signal {
                connection,
                first,
                source,
                state,
            } => {
                let owner = self.0.get(&source.pane);
                if first && owner.is_none_or(|old| old.id < connection) {
                    self.0.insert(
                        source.pane.clone(),
                        PaneConnection {
                            id: connection,
                            active: true,
                        },
                    );
                    Some((source, state))
                } else if !first && owner.is_some_and(|old| old.active && old.id == connection) {
                    Some((source, state))
                } else {
                    None
                }
            }
            Event::Closed { connection, source } => {
                let owner = self.0.get_mut(&source.pane);
                if let Some(owner) = owner.filter(|old| old.active && old.id == connection) {
                    // Keep the watermark so a delayed first frame can't resurrect an old stream.
                    owner.active = false;
                    Some((source, "gone"))
                } else {
                    None
                }
            }
            Event::ListenerFailed(err) => return Err(err),
        };
        Ok(output)
    }
}

fn bind(path: &Path) -> io::Result<UnixListener> {
    if !path.is_absolute() || path.file_name().is_none() {
        return Err(io::Error::other("expected an absolute socket path"));
    }
    let parent = fs::symlink_metadata(path.parent().expect("absolute socket has a parent"))?;
    // On Linux /proc/self is owned by the process's effective user for this binary.
    let uid = fs::metadata("/proc/self")?.uid();
    if !parent.is_dir() || parent.uid() != uid || parent.permissions().mode() & 0o777 != 0o700 {
        return Err(io::Error::other(
            "socket parent must be a directory owned by you with mode 0700",
        ));
    }
    // Never remove or replace an existing entry, even if it looks like a socket.
    UnixListener::bind(path)
}

fn receive(path: &Path) -> io::Result<()> {
    let listener = bind(path)?;
    let (events, incoming) = mpsc::sync_channel(64);
    thread::spawn(move || {
        for (connection, stream) in (0..).zip(listener.incoming()) {
            match stream {
                Ok(stream) => {
                    let events = events.clone();
                    thread::spawn(move || read_stream(connection, stream, events));
                }
                Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
                Err(err) => {
                    let _ = events.send(Event::ListenerFailed(err));
                    break;
                }
            }
        }
    });

    let mut panes = Panes::default();
    let mut stdout = io::stdout().lock();
    for event in incoming {
        if let Some((source, state)) = panes.observe(event)? {
            writeln!(stdout, "{}\t{}\t{state}", source.pane, source.instance)?;
            stdout.flush()?;
        }
    }
    Ok(())
}

fn main() -> io::Result<()> {
    let mut args = env::args_os().skip(1);
    let path = args
        .next()
        .filter(|_| args.next().is_none())
        .ok_or_else(|| {
            io::Error::other("usage: pi-tmux-signals /absolute/private-directory/signals.sock")
        })?;
    match receive(Path::new(&path)) {
        Err(err) if err.kind() == io::ErrorKind::BrokenPipe => Ok(()),
        result => result,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const OLD: &str = "0123456789abcdef0123456789abcdef";
    const NEW: &str = "0123456789abcdef0123456789abcdea";

    #[test]
    fn accepts_only_complete_state_frames() {
        let valid = format!("%0\t{OLD}\tneeds_permission\n");
        assert_eq!(
            read_signal(&mut valid.as_bytes()).unwrap().1,
            "needs_permission"
        );
        for invalid in [
            format!("%0\t{OLD}\tidle"),
            format!("%0\t{OLD}\tgone\n"),
            format!("%0\t{OLD}\tidle\textra\n"),
            format!("%0\t{OLD}\t{}\n", "x".repeat(64)),
            format!("\x1b[0m\t{OLD}\tidle\n"),
            "%0\tsession/path\tidle\n".into(),
        ] {
            assert!(read_signal(&mut invalid.as_bytes()).is_none());
        }
    }

    #[test]
    fn late_updates_and_exit_cannot_affect_replacement_or_other_pane() {
        let mut panes = Panes::default();
        let original = Source {
            pane: "%0".into(),
            instance: OLD.into(),
        };
        let replacement = Source {
            pane: "%0".into(),
            instance: NEW.into(),
        };
        let other = Source {
            pane: "%1".into(),
            instance: OLD.into(),
        };
        let signal = |connection, first, source: &Source, state| Event::Signal {
            connection,
            first,
            source: source.clone(),
            state,
        };
        let closed = |connection, source: &Source| Event::Closed {
            connection,
            source: source.clone(),
        };
        let mut observe = |event| panes.observe(event).unwrap();
        assert_eq!(
            observe(signal(0, true, &original, "done")),
            Some((original.clone(), "done"))
        );
        assert_eq!(
            observe(signal(1, true, &other, "idle")),
            Some((other.clone(), "idle"))
        );
        assert_eq!(
            observe(signal(3, true, &replacement, "idle")),
            Some((replacement.clone(), "idle"))
        );
        assert_eq!(
            observe(signal(0, false, &original, "needs_permission")),
            None
        );
        assert_eq!(observe(closed(0, &original)), None);
        assert_eq!(
            observe(closed(3, &replacement)),
            Some((replacement.clone(), "gone"))
        );
        assert_eq!(observe(signal(2, true, &original, "done")), None);
        assert_eq!(observe(signal(2, false, &original, "idle")), None);
        assert_eq!(observe(closed(2, &original)), None);
        assert_eq!(
            observe(signal(4, true, &replacement, "busy")),
            Some((replacement, "busy"))
        );
        assert_eq!(
            observe(signal(1, false, &other, "busy")),
            Some((other.clone(), "busy"))
        );
        assert_eq!(observe(closed(1, &other)), Some((other, "gone")));
    }
}
