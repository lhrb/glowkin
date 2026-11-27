package dev.lhrb.app.glowkin.features.connection.domain

import org.junit.Assert.*
import org.junit.Test

class PiChatTest {
    private val source = PiChatSnapshot("pi-one", "r1", "pc", emptyList(), null, PiActivity.IDLE,
        queued = false, inputEnabled = true)
    private val attached = PiPaneRoute.Chat(source.instanceId, source)
    private val edit = PiDraftEdit(attached.editableDraft!!, "phone")

    @Test fun `only unbound unsupported and bound exit route to terminal`() {
        val probe = PiPaneRoute.Chat()
        assertEquals(PiPaneRoute.Terminal, probe.afterRead(PiChatRead.Unsupported))
        assertEquals(source, (probe.afterRead(PiChatRead.Available(source)) as PiPaneRoute.Chat).snapshot)
        assertTrue(probe.afterRead(PiChatRead.Exited) is PiPaneRoute.Chat)
        assertTrue(probe.afterRead(PiChatRead.Unavailable) is PiPaneRoute.Chat)
        assertEquals(PiPaneRoute.Terminal, attached.afterRead(PiChatRead.Exited))
        for (result in listOf(PiChatRead.Unsupported, PiChatRead.Unavailable, PiChatRead.Conflict,
            PiChatRead.Available(source.copy(instanceId = "pi-two")))) {
            val paused = attached.afterRead(result) as PiPaneRoute.Chat
            assertEquals(source.instanceId, paused.instanceId)
            assertNull(paused.editableDraft)
        }
    }

    @Test fun `a pending edit never survives a changed revision or blocked composer`() {
        assertTrue(attached.accepts(edit))
        assertTrue(attached.accepts(edit.copy(text = "")))
        assertFalse(attached.accepts(edit.copy(text = "pc")))
        assertFalse(attached.accepts(edit.copy(text = "bad\r")))
        val sameDraft = source.copy(activity = PiActivity.WORKING, queued = true)
        assertTrue((attached.afterRead(PiChatRead.Available(sameDraft)) as PiPaneRoute.Chat).accepts(edit))
        for (snapshot in listOf(source.copy(draftRevision = "r2"), source.copy(inputEnabled = false),
            source.copy(draft = null), source.copy(activity = PiActivity.COMPACTING),
            source.copy(activity = PiActivity.RESPOND_ON_PC))) {
            assertFalse((attached.afterRead(PiChatRead.Available(snapshot)) as PiPaneRoute.Chat).accepts(edit))
        }
    }

    @Test fun `all write outcomes block another edit until an authoritative read`() {
        for (outcome in ChangeOutcome.entries) {
            val blocked = attached.afterWrite(outcome)
            assertFalse(blocked.accepts(edit))
            val stillUnavailable = blocked.afterRead(PiChatRead.Unavailable) as PiPaneRoute.Chat
            assertFalse(stillUnavailable.accepts(edit))
            val reconciled = blocked.afterRead(PiChatRead.Available(source.copy(draftRevision = "r2", draft = "latest")))
                as PiPaneRoute.Chat
            assertFalse(reconciled.accepts(edit))
            assertTrue(reconciled.accepts(PiDraftEdit(reconciled.editableDraft!!, "new explicit edit")))
        }
    }

    @Test fun `an unchanged draft after a pause cannot revive a pre-pause edit`() {
        val paused = attached.afterRead(PiChatRead.Unavailable) as PiPaneRoute.Chat
        val recovered = paused.afterRead(PiChatRead.Available(source)) as PiPaneRoute.Chat
        assertEquals(edit.basis, recovered.editableDraft!!.basis)
        assertFalse(recovered.accepts(edit))
    }

    @Test fun `draft limits count UTF8 bytes and permit only LF and tab controls`() {
        assertTrue(isValidPiDraft("π\n\t"))
        assertTrue(isValidPiDraft("😀".repeat(4096)))
        assertFalse(isValidPiDraft("😀".repeat(4096) + "x"))
        for (invalid in listOf("\r", "\u0000", "\u007f", "\u0085", "\u2028", "\u2029", "\ud800")) {
            assertFalse(isValidPiDraft(invalid))
        }
    }
}
