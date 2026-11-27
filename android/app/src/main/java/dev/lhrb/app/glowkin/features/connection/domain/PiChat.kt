package dev.lhrb.app.glowkin.features.connection.domain

internal enum class ChatRole { USER, ASSISTANT, TOOL }
internal enum class PiActivity { IDLE, WORKING, COMPACTING, RESPOND_ON_PC }
internal data class ChatMessage(val id: String, val role: ChatRole, val text: String, val tool: String?)
internal data class PiChatSnapshot(
    val instanceId: String,
    val draftRevision: String,
    val draft: String?,
    val messages: List<ChatMessage>,
    val subagents: List<String>?,
    val activity: PiActivity,
    val queued: Boolean,
    val inputEnabled: Boolean,
) {
    val editableDraft: PiDraftBasis?
        get() = draft?.takeIf { inputEnabled && (activity == PiActivity.IDLE || activity == PiActivity.WORKING) }
            ?.let { PiDraftBasis(instanceId, draftRevision, it) }
}

internal data class PiDraftBasis(val instanceId: String, val revision: String, val text: String)
// A new edit window after a blocker gets a new permit even if Pi's draft revision did not change.
internal class PiDraftPermit(val basis: PiDraftBasis)
internal data class PiDraftEdit(val permit: PiDraftPermit, val text: String) {
    val basis: PiDraftBasis get() = permit.basis
}

internal enum class PiChatPause { UNAVAILABLE, CONFLICT, SYNCING, UNCERTAIN, INPUT_DISABLED }

internal sealed interface PiPaneRoute {
    data object Terminal : PiPaneRoute
    data class Chat(
        val instanceId: String? = null,
        val snapshot: PiChatSnapshot? = null,
        val pause: PiChatPause? = null,
        private val permit: PiDraftPermit? = snapshot?.editableDraft?.let(::PiDraftPermit),
    ) : PiPaneRoute {
        val editableDraft: PiDraftPermit? get() = permit?.takeIf { pause == null }

        fun accepts(edit: PiDraftEdit): Boolean = editableDraft === edit.permit &&
            edit.basis.text != edit.text && isValidPiDraft(edit.text)

        fun afterWrite(outcome: ChangeOutcome): Chat = copy(pause = when (outcome) {
            ChangeOutcome.APPLIED -> PiChatPause.SYNCING
            ChangeOutcome.TARGET_UNAVAILABLE -> PiChatPause.CONFLICT
            ChangeOutcome.REJECTED -> PiChatPause.INPUT_DISABLED
            ChangeOutcome.UNCERTAIN, ChangeOutcome.RECONCILIATION_REQUIRED -> PiChatPause.UNCERTAIN
            ChangeOutcome.NOT_CONNECTED -> PiChatPause.UNAVAILABLE
        })

        fun afterRead(result: PiChatRead): PiPaneRoute = when (result) {
            is PiChatRead.Available -> if (instanceId == null || instanceId == result.snapshot.instanceId) {
                val basis = result.snapshot.editableDraft
                val newPermit = editableDraft?.takeIf { it.basis == basis } ?: basis?.let(::PiDraftPermit)
                Chat(result.snapshot.instanceId, result.snapshot, permit = newPermit)
            } else copy(pause = PiChatPause.CONFLICT)
            PiChatRead.Unsupported -> if (instanceId == null) Terminal else copy(pause = PiChatPause.UNAVAILABLE)
            PiChatRead.Exited -> if (instanceId != null) Terminal else copy(pause = PiChatPause.UNAVAILABLE)
            PiChatRead.Conflict -> copy(pause = PiChatPause.CONFLICT)
            PiChatRead.Unavailable -> copy(pause = if (pause == PiChatPause.UNCERTAIN) pause else PiChatPause.UNAVAILABLE)
        }
    }
}

internal sealed interface PiChatRead {
    data class Available(val snapshot: PiChatSnapshot) : PiChatRead
    data object Unsupported : PiChatRead
    data object Exited : PiChatRead
    data object Conflict : PiChatRead
    data object Unavailable : PiChatRead
}

internal fun isValidPiDraft(text: String): Boolean = text.length <= 16384 &&
    text.none { (it.isISOControl() && it != '\n' && it != '\t') || it == '\u2028' || it == '\u2029' } &&
    Charsets.UTF_8.newEncoder().canEncode(text) && text.toByteArray(Charsets.UTF_8).size <= 16384
