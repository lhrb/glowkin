use super::{
    PaneTarget, Tmux,
    pi_chat_socket::{self, ChatSnapshot},
};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) struct PiInstanceId<'a>(&'a str);

impl<'a> PiInstanceId<'a> {
    pub(crate) fn new(id: &'a str) -> Option<Self> {
        (id.len() == 32
            && id
                .bytes()
                .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f')))
        .then_some(Self(id))
    }

    pub(super) fn as_str(self) -> &'a str {
        self.0
    }
}

pub(crate) struct DraftText<'a>(&'a str);

impl<'a> DraftText<'a> {
    pub(crate) fn new(text: &'a str) -> Option<Self> {
        (text.len() <= 16384
            && !text.chars().any(|ch| {
                (ch.is_control() && !matches!(ch, '\n' | '\t'))
                    || matches!(ch, '\u{2028}' | '\u{2029}')
            }))
        .then_some(Self(text))
    }

    pub(super) fn as_str(&self) -> &str {
        self.0
    }
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum PiChatError {
    Unsupported,
    Exited,
    Conflict,
    Uncertain,
}

pub(crate) async fn read_pi_chat(
    tmux: &Tmux,
    target: PaneTarget,
    expected: Option<PiInstanceId<'_>>,
) -> Result<ChatSnapshot, PiChatError> {
    let marker = tmux.pi_chat_marker(&target).await?;
    let instance = active_instance(marker.as_deref(), expected)?;
    pi_chat_socket::read(&target, instance).await
}

pub(crate) async fn edit_pi_draft(
    tmux: &Tmux,
    target: PaneTarget,
    expected: PiInstanceId<'_>,
    draft_revision: &str,
    text: DraftText<'_>,
) -> Result<(), PiChatError> {
    let marker = tmux.pi_chat_marker(&target).await?;
    let instance = active_instance(marker.as_deref(), Some(expected))?;
    pi_chat_socket::edit(&target, instance, draft_revision, text).await
}

fn active_instance<'a>(
    marker: Option<&'a str>,
    expected: Option<PiInstanceId<'_>>,
) -> Result<PiInstanceId<'a>, PiChatError> {
    let Some(marker) = marker else {
        return Err(if expected.is_some() {
            PiChatError::Conflict
        } else {
            PiChatError::Unsupported
        });
    };
    let (state, id) = marker.split_once(':').ok_or(PiChatError::Uncertain)?;
    let id = PiInstanceId::new(id).ok_or(PiChatError::Uncertain)?;
    if !matches!(state, "active" | "exited") {
        return Err(PiChatError::Uncertain);
    }
    if expected.is_some_and(|expected| expected != id) {
        return Err(PiChatError::Conflict);
    }
    match state {
        "exited" if expected.is_some() => Err(PiChatError::Exited),
        "exited" => Err(PiChatError::Unsupported),
        _ => Ok(id),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distinguishes_absence_exit_replacement_and_uncertainty() {
        use PiChatError::*;
        let id = "0123456789abcdef0123456789abcdef";
        let active = format!("active:{id}");
        let exited = format!("exited:{id}");
        let instance = PiInstanceId::new(id).unwrap();
        let other = PiInstanceId::new("fedcba9876543210fedcba9876543210");
        assert_eq!(active_instance(None, None).err(), Some(Unsupported));
        assert_eq!(active_instance(None, Some(instance)).err(), Some(Conflict));
        assert_eq!(
            active_instance(Some(&exited), None).err(),
            Some(Unsupported)
        );
        assert_eq!(
            active_instance(Some(&exited), Some(instance)).err(),
            Some(Exited)
        );
        assert_eq!(active_instance(Some(&exited), other).err(), Some(Conflict));
        assert_eq!(active_instance(Some(&active), other).err(), Some(Conflict));
        assert_eq!(
            active_instance(Some(&active), None)
                .ok()
                .map(PiInstanceId::as_str),
            Some(id)
        );
        assert!(active_instance(Some(&active), Some(instance)).is_ok());
        for marker in [
            "",
            "active:0123",
            "active:0123456789ABCDEF0123456789abcdef",
            "unknown:0123456789abcdef0123456789abcdef",
        ] {
            assert_eq!(
                active_instance(Some(marker), Some(instance)).err(),
                Some(Uncertain)
            );
        }
    }

    #[test]
    fn draft_validation_counts_utf8_and_allows_empty_multiline_text() {
        assert!(DraftText::new("").is_some());
        assert!(DraftText::new(" π\n\t ").is_some());
        assert!(DraftText::new(&"😀".repeat(4096)).is_some());
        assert!(DraftText::new(&"😀".repeat(4097)).is_none());
        for forbidden in [
            '\0', '\r', '\u{1b}', '\u{7f}', '\u{85}', '\u{2028}', '\u{2029}',
        ] {
            assert!(DraftText::new(&forbidden.to_string()).is_none());
        }
    }
}
