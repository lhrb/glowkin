use std::{
    fs::{self, Metadata},
    io,
    os::unix::fs::{FileTypeExt, MetadataExt},
    path::{Path, PathBuf},
    time::Duration,
};

use serde::{Deserialize, Serialize, de::DeserializeOwned};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader},
    net::UnixStream,
    time::timeout,
};

use super::{
    PaneTarget,
    pi_chat::{DraftText, PiChatError, PiInstanceId},
};

pub(crate) const REQUEST_LIMIT: usize = 128 * 1024;
const REPLY_LIMIT: usize = 256 * 1024;
const DEADLINE: Duration = Duration::from_secs(2);

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Request<'a> {
    #[serde(flatten)]
    operation: Operation<'a>,
    session_id: String,
    generation: String,
    window_id: String,
    pane_id: String,
    instance_id: &'a str,
}

#[derive(Serialize)]
#[serde(tag = "op", rename_all = "lowercase", rename_all_fields = "camelCase")]
enum Operation<'a> {
    Read,
    Edit {
        draft_revision: &'a str,
        text: &'a str,
    },
}

#[derive(Deserialize)]
#[serde(tag = "status", rename_all = "lowercase")]
enum ReadReply {
    Ok(ChatSnapshot),
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ChatSnapshot {
    instance_id: String,
    draft_revision: String,
    #[serde(deserialize_with = "Deserialize::deserialize")]
    draft: Option<String>,
    messages: Vec<ChatMessage>,
    #[serde(deserialize_with = "Deserialize::deserialize")]
    subagents: Option<Vec<String>>,
    activity: Activity,
    queued: bool,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct ChatMessage {
    id: String,
    role: Role,
    text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool: Option<String>,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Role {
    User,
    Assistant,
    Tool,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Activity {
    Idle,
    Working,
    Compacting,
    RespondOnPc,
}

#[derive(Deserialize)]
#[serde(tag = "status", rename_all = "lowercase", deny_unknown_fields)]
enum EditReply {
    Ok {},
    Conflict {},
    Blocked {},
    Unavailable {},
}

pub(super) async fn read(
    target: &PaneTarget,
    instance: PiInstanceId<'_>,
) -> Result<ChatSnapshot, PiChatError> {
    let ReadReply::Ok(snapshot) = exchange(target, instance, Operation::Read).await?;
    if snapshot.instance_id != instance.as_str()
        || snapshot
            .draft
            .as_deref()
            .is_some_and(|draft| DraftText::new(draft).is_none())
    {
        return Err(PiChatError::Uncertain);
    }
    Ok(snapshot)
}

pub(super) async fn edit(
    target: &PaneTarget,
    instance: PiInstanceId<'_>,
    draft_revision: &str,
    text: DraftText<'_>,
) -> Result<(), PiChatError> {
    match exchange(
        target,
        instance,
        Operation::Edit {
            draft_revision,
            text: text.as_str(),
        },
    )
    .await?
    {
        EditReply::Ok {} => Ok(()),
        EditReply::Conflict {} | EditReply::Blocked {} | EditReply::Unavailable {} => {
            Err(PiChatError::Conflict)
        }
    }
}

async fn exchange<T: DeserializeOwned>(
    target: &PaneTarget,
    instance: PiInstanceId<'_>,
    operation: Operation<'_>,
) -> Result<T, PiChatError> {
    let request = Request {
        operation,
        session_id: format!("${}", target.session.session_id),
        generation: format!(
            "{}-{}",
            target.session.server_pid, target.session.server_start
        ),
        window_id: format!("@{}", target.window_id),
        pane_id: format!("%{}", target.pane_id),
        instance_id: instance.as_str(),
    };
    // SAFETY: geteuid has no arguments or memory preconditions.
    let uid = unsafe { libc::geteuid() };
    let directory = PathBuf::from(format!("/tmp/glowkin-chat-{uid}"));
    timeout(DEADLINE, exchange_at(&directory, uid, &request))
        .await
        .map_err(|_| PiChatError::Uncertain)?
}

async fn exchange_at<T: DeserializeOwned>(
    directory: &Path,
    uid: u32,
    request: &Request<'_>,
) -> Result<T, PiChatError> {
    let mut bytes = serde_json::to_vec(request).map_err(|_| PiChatError::Uncertain)?;
    bytes.push(b'\n');
    if bytes.len() > REQUEST_LIMIT {
        return Err(PiChatError::Uncertain);
    }
    let mut stream = connect(directory, uid, request.instance_id)
        .await
        .map_err(|_| PiChatError::Uncertain)?;
    stream
        .write_all(&bytes)
        .await
        .map_err(|_| PiChatError::Uncertain)?;
    bytes.clear();
    BufReader::new(stream)
        .take((REPLY_LIMIT + 1) as u64)
        .read_until(b'\n', &mut bytes)
        .await
        .map_err(|_| PiChatError::Uncertain)?;
    if bytes.len() > REPLY_LIMIT
        || bytes.pop() != Some(b'\n')
        || bytes.iter().find(|b| !b.is_ascii_whitespace()) != Some(&b'{')
    {
        return Err(PiChatError::Uncertain);
    }
    serde_json::from_slice(&bytes).map_err(|_| PiChatError::Uncertain)
}

async fn connect(directory: &Path, uid: u32, id: &str) -> io::Result<UnixStream> {
    let socket = directory.join(format!("{id}.sock"));
    let check_paths = || -> io::Result<_> {
        let dir = fs::symlink_metadata(directory)?;
        if !dir.is_dir() || dir.uid() != uid || dir.mode() & 0o7777 != 0o700 {
            return Err(io::ErrorKind::PermissionDenied.into());
        }
        let sock = fs::symlink_metadata(&socket)?;
        if !sock.file_type().is_socket() || sock.uid() != uid || sock.mode() & 0o7777 != 0o600 {
            return Err(io::ErrorKind::PermissionDenied.into());
        }
        Ok((file_id(&dir), file_id(&sock)))
    };
    let before = check_paths()?;
    let stream = UnixStream::connect(&socket).await?;
    if stream.peer_cred()?.uid() != uid || check_paths()? != before {
        return Err(io::ErrorKind::PermissionDenied.into());
    }
    Ok(stream)
}

fn file_id(metadata: &Metadata) -> (u64, u64) {
    (metadata.dev(), metadata.ino())
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::{DirBuilderExt, PermissionsExt, symlink};

    use serde_json::{Value, json};
    use tokio::net::UnixListener;
    use uuid::Uuid;

    use super::*;

    const ID: &str = "0123456789abcdef0123456789abcdef";

    struct Fixture {
        root: PathBuf,
        uid: u32,
    }

    impl Fixture {
        fn new() -> Self {
            // /tmp and a short unique directory keep paths below sockaddr_un's limit.
            let root = PathBuf::from(format!("/tmp/glowkin-test-{}", Uuid::new_v4().simple()));
            fs::DirBuilder::new().mode(0o700).create(&root).unwrap();
            let uid = fs::symlink_metadata(&root).unwrap().uid();
            Self { root, uid }
        }

        fn socket(&self) -> PathBuf {
            self.root.join(format!("{ID}.sock"))
        }

        fn bind(&self) -> UnixListener {
            let socket = self.socket();
            let listener = UnixListener::bind(&socket).unwrap();
            set_mode(&socket, 0o600);
            listener
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.root).unwrap();
        }
    }

    fn set_mode(path: &Path, mode: u32) {
        fs::set_permissions(path, fs::Permissions::from_mode(mode)).unwrap();
    }

    fn request(operation: Operation<'_>) -> Request<'_> {
        Request {
            operation,
            session_id: "$1".into(),
            generation: "2-3".into(),
            window_id: "@4".into(),
            pane_id: "%5".into(),
            instance_id: ID,
        }
    }

    async fn fake_pi(listener: UnixListener, reply: &[u8]) -> Value {
        let (stream, _) = listener.accept().await.unwrap();
        let mut stream = BufReader::new(stream);
        let mut request = Vec::new();
        stream.read_until(b'\n', &mut request).await.unwrap();
        let _ = stream.get_mut().write_all(reply).await;
        serde_json::from_slice(&request).unwrap()
    }

    #[tokio::test]
    async fn exchanges_one_edit_with_exact_identity_and_literal_draft() {
        let fixture = Fixture::new();
        let request = request(Operation::Edit {
            draft_revision: "13",
            text: "π\n\t",
        });
        let (result, seen) = timeout(DEADLINE, async {
            tokio::join!(
                exchange_at::<EditReply>(&fixture.root, fixture.uid, &request),
                fake_pi(fixture.bind(), b"{\"status\":\"ok\"}\n")
            )
        })
        .await
        .unwrap();
        assert!(matches!(result, Ok(EditReply::Ok {})));
        assert_eq!(
            seen,
            json!({
                "op":"edit", "sessionId":"$1", "generation":"2-3", "windowId":"@4", "paneId":"%5",
                "instanceId":ID,"draftRevision":"13","text":"π\n\t",
            })
        );
    }

    #[tokio::test]
    async fn accepts_one_bounded_chat_snapshot_line() {
        let fixture = Fixture::new();
        let response = json!({
            "status":"ok","instanceId":ID,"draftRevision":"13","draft":null,
            "messages":[{"id":"1","role":"tool","text":"output","tool":"read"}],
            "subagents":null,"activity":"respond_on_pc","queued":false,
        });
        let wire = format!("{response}\n");
        let request = request(Operation::Read);
        let (result, seen) = timeout(DEADLINE, async {
            tokio::join!(
                exchange_at::<ReadReply>(&fixture.root, fixture.uid, &request),
                fake_pi(fixture.bind(), wire.as_bytes())
            )
        })
        .await
        .unwrap();
        assert_eq!(
            seen,
            json!({"op":"read","sessionId":"$1","generation":"2-3","windowId":"@4","paneId":"%5","instanceId":ID})
        );
        let ReadReply::Ok(snapshot) = result.unwrap();
        let actual = serde_json::to_value(snapshot).unwrap();
        let mut expected = response;
        expected.as_object_mut().unwrap().remove("status");
        assert_eq!(actual, expected);
        expected.as_object_mut().unwrap().remove("draft");
        expected["status"] = "ok".into();
        assert!(serde_json::from_value::<ReadReply>(expected).is_err());
    }

    #[tokio::test]
    async fn rejects_unsafe_socket_paths_and_permissions() {
        let fixture = Fixture::new();
        let _listener = fixture.bind();
        assert!(connect(&fixture.root, fixture.uid, ID).await.is_ok());
        assert!(
            connect(&fixture.root, fixture.uid.wrapping_add(1), ID)
                .await
                .is_err()
        );
        set_mode(&fixture.root, 0o755);
        assert!(connect(&fixture.root, fixture.uid, ID).await.is_err());
        set_mode(&fixture.root, 0o700);
        set_mode(&fixture.socket(), 0o660);
        assert!(connect(&fixture.root, fixture.uid, ID).await.is_err());
        set_mode(&fixture.socket(), 0o600);
        symlink(&fixture.root, fixture.root.join("alias")).unwrap();
        assert!(
            connect(&fixture.root.join("alias"), fixture.uid, ID)
                .await
                .is_err()
        );
        fs::rename(fixture.socket(), fixture.root.join("real.sock")).unwrap();
        symlink(fixture.root.join("real.sock"), fixture.socket()).unwrap();
        assert!(connect(&fixture.root, fixture.uid, ID).await.is_err());
    }

    #[tokio::test]
    async fn rejects_malformed_unterminated_and_oversized_replies() {
        let mut oversized = vec![b' '; REPLY_LIMIT];
        oversized.extend(b"{\"status\":\"ok\"}\n");
        for reply in [
            b"{\"status\":\"ok\"}".as_slice(),
            b"{\"status\":\"unknown\"}\n",
            b"[\"ok\"]\n",
            &oversized,
        ] {
            let fixture = Fixture::new();
            let request = request(Operation::Edit {
                draft_revision: "1",
                text: "",
            });
            let (result, _) = timeout(DEADLINE, async {
                tokio::join!(
                    exchange_at::<EditReply>(&fixture.root, fixture.uid, &request),
                    fake_pi(fixture.bind(), reply)
                )
            })
            .await
            .unwrap();
            assert!(matches!(result, Err(PiChatError::Uncertain)));
        }
    }
}
