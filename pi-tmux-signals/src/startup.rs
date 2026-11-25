use crate::{Chooser, bind, receive};
use std::fs::{self, File, OpenOptions};
use std::io;
use std::os::unix::fs::{FileTypeExt, MetadataExt, OpenOptionsExt};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

static STOPPING: AtomicBool = AtomicBool::new(false);

pub(crate) fn stopping() -> bool {
    STOPPING.load(Ordering::Relaxed)
}

extern "C" fn stop(_: libc::c_int) {
    STOPPING.store(true, Ordering::Relaxed);
}

fn handle_shutdown() -> io::Result<()> {
    // tmux may signal its jobs on shutdown. Let the owning thread remove the socket.
    // SAFETY: each action has an empty signal mask and an async-signal-safe handler.
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = stop as *const () as libc::sighandler_t;
        action.sa_flags = libc::SA_RESTART;
        libc::sigemptyset(&mut action.sa_mask);
        for signal in [libc::SIGHUP, libc::SIGTERM, libc::SIGINT] {
            if libc::sigaction(signal, &action, std::ptr::null_mut()) != 0 {
                return Err(io::Error::last_os_error());
            }
        }
    }
    Ok(())
}

struct OwnedSocket<'a> {
    path: &'a Path,
    // Hold the inode so an unrelated replacement cannot reuse its identity.
    entry: File,
}

impl Drop for OwnedSocket<'_> {
    fn drop(&mut self) {
        let Ok(owned) = self.entry.metadata() else {
            return;
        };
        if !fs::symlink_metadata(self.path)
            .is_ok_and(|current| current.dev() == owned.dev() && current.ino() == owned.ino())
        {
            return;
        }
        if let Err(err) = fs::remove_file(self.path) {
            eprintln!("could not clean up Pi signal socket: {err}");
        }
    }
}

pub(crate) fn serve(path: &Path, chooser: Chooser) -> io::Result<()> {
    if path.to_str().is_none() {
        return Err(io::Error::other("expected a UTF-8 signal socket path"));
    }
    handle_shutdown()?;
    let deadline = Instant::now() + Duration::from_secs(4);
    let listener = loop {
        if stopping() || !chooser.is_current() {
            return Ok(());
        }
        match bind(path) {
            Ok(listener) => break listener,
            Err(err) if err.kind() == io::ErrorKind::AddrInUse && Instant::now() < deadline => {
                // A receiver from the preceding server may still be winding down.
                // Wait for it to remove its own entry; never remove a blocker.
                thread::sleep(Duration::from_millis(100));
            }
            Err(err) => return Err(err),
        }
    };
    let entry = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_PATH | libc::O_NOFOLLOW)
        .open(path)?;
    let endpoint = entry.metadata()?;
    if !endpoint.file_type().is_socket() {
        return Err(io::Error::other("the Pi signal socket was replaced"));
    }
    let _owned = OwnedSocket { path, entry };
    if stopping() || !chooser.is_current() {
        return Ok(());
    }
    chooser.advertise(path, &endpoint)?;
    match receive(listener, Some(chooser), false) {
        Err(err) if err.kind() == io::ErrorKind::NotConnected => Ok(()),
        result => result,
    }
}
