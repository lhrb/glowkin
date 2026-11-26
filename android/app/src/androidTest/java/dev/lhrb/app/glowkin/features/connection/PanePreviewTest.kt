package dev.lhrb.app.glowkin.features.connection

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.lhrb.app.glowkin.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PanePreviewTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectionReadsBeforeTheNextWorkspacePoll() = withApp { server, _ ->
        val snapshots = server.snapshotRequests
        click("Pane A")
        val read = server.nextRead("%1")
        read.reply("first capture")
        assertEquals("selection must not wait for another snapshot", snapshots, read.snapshotRequests)
        awaitText("first capture")
    }

    @Test fun reselectionDiscardsDelayedResponseEvenForTheSamePane() = withApp { server, _ ->
        click("Pane A")
        val old = server.nextRead("%1")
        click("Overview")
        click("Pane B")
        click("Overview")
        click("Pane A")
        old.reply("obsolete A")

        val fresh = server.nextRead("%1")
        compose.onNodeWithText("obsolete A").assertDoesNotExist()
        fresh.reply("fresh A\n    indented\n")
        awaitText("fresh A\n    indented\n")

        click("Overview")
        click("Pane B")
        compose.onNodeWithText("fresh A", substring = true).assertDoesNotExist()
        server.nextRead("%2").reply("current B")
        awaitText("current B")

        server.secondPane = false
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithText("current B").fetchSemanticsNodes().isEmpty()
        }
        awaitText("Pane is no longer available. Select a window again.")
        assertNull("ineligible selection must stop reading", server.reads.poll(2300, TimeUnit.MILLISECONDS))
        compose.onNodeWithText("2 windows").assertIsDisplayed()
        click("Overview")
        compose.onNodeWithText("Pane B").assertHasNoClickAction()
    }

    @Test fun stopsReadsInBackgroundAndDropsThePreviousForegroundsDelivery() = withApp { server, activity ->
        click("Pane A")
        val old = server.nextRead("%1")
        activity.moveToState(Lifecycle.State.CREATED)
        old.reply("previous foreground")
        assertNull("background must not read panes", server.reads.poll(2300, TimeUnit.MILLISECONDS))

        activity.moveToState(Lifecycle.State.RESUMED)
        val current = server.nextRead("%1")
        compose.onNodeWithText("previous foreground").assertDoesNotExist()
        current.reply("current foreground")
        awaitText("current foreground")
    }

    @Test fun backReturnsToOverviewAndDiscardsAnInFlightRead() = withApp { server, activity ->
        click("Pane A")
        server.nextRead("%1").reply("detail content")
        awaitText("detail content")
        compose.onNodeWithText("Pane B").assertDoesNotExist()
        val delayed = server.nextRead("%1")
        compose.waitForIdle()
        back(activity)
        delayed.reply("after Back")
        awaitText("Pane B")
        compose.onNodeWithText("detail content").assertDoesNotExist()
        assertNull("Back must stop reads", server.reads.poll(2300, TimeUnit.MILLISECONDS))
        compose.onNodeWithText("after Back").assertDoesNotExist()
    }

    @Test fun bottomOverviewDiscardsAnInFlightRead() = withApp { server, _ ->
        click("Pane A")
        server.nextRead("%1").reply("detail before overview")
        awaitText("detail before overview")
        val delayed = server.nextRead("%1")
        click("Overview")
        delayed.reply("late after overview")
        awaitText("Pane B")
        compose.onNodeWithText("detail before overview").assertDoesNotExist()
        assertNull("bottom overview must stop reads", server.reads.poll(2300, TimeUnit.MILLISECONDS))
        compose.onNodeWithText("late after overview").assertDoesNotExist()
    }

    @Test fun connectionFromDetailClearsSelectionEvenWhenReturningWithBack() = withApp { server, activity ->
        click("Pane A")
        server.nextRead("%1").reply("detail before connection")
        awaitText("detail before connection")
        val delayed = server.nextRead("%1")
        click("Connection")
        delayed.reply("late after connection")
        awaitText("PC bridge")
        compose.onNodeWithText("detail before connection").assertDoesNotExist()
        assertNull("connection page must stop reads", server.reads.poll(2300, TimeUnit.MILLISECONDS))

        back(activity)
        awaitText("Pane B")
        compose.onNodeWithText("late after connection").assertDoesNotExist()
        assertNull("leaving connection must not reopen detail", server.reads.poll(2300, TimeUnit.MILLISECONDS))
    }

    @Test fun losingConnectionClearsPreviewAndRecoveryDoesNotReopenDetail() = withApp { server, _ ->
        click("Pane A")
        server.nextRead("%1").reply("before loss")
        awaitText("before loss")
        compose.onNodeWithText("2 windows").assertIsDisplayed()
        server.failSnapshots = true
        awaitText("Disconnected")
        compose.onNodeWithText("before loss").assertDoesNotExist()
        compose.onNodeWithText("2 windows").assertDoesNotExist()
        compose.onNodeWithText("Window count unavailable").assertIsDisplayed()

        server.failSnapshots = false
        awaitText("Pane B")
        compose.onNodeWithText("2 windows").assertIsDisplayed()
        assertNull("recovery must not reopen the pane", server.reads.poll(2300, TimeUnit.MILLISECONDS))
        click("Pane A")
        server.nextRead("%1").reply("opened again")
        awaitText("opened again")
    }

    @Test fun inputSendsLiteralTextAndSeparateKeyToTheExactPane() = withApp(inputCapability = "true") { server, _ ->
        click("Pane B")
        server.nextRead("%2").reply("PC draft")
        awaitText("PC draft")
        val draft = " + Android π; \"quoted\""
        draftField().performTextReplacement(draft)
        click("Insert text")
        val insert = server.nextInput("%2")
        assertEquals(1, insert.body.getJSONObject("input").length())
        assertEquals(draft, insert.body.getJSONObject("input").getString("text"))
        insert.reply("204 No Content")
        awaitText("Input injected. Check the pane for its effect.")
        draftField().assert(!hasText(draft, substring = true))
        click("Enter")
        val enter = server.nextInput("%2")
        assertEquals(1, enter.body.getJSONObject("input").length())
        assertEquals("enter", enter.body.getJSONObject("input").getString("key"))
        enter.reply("403 Forbidden")
        awaitText("The bridge rejected input. No input was injected.")
        assertEquals(2, server.inputCount)
    }

    @Test fun capabilityMustBeExplicitAndNeverProbesWithInput() = withApp { server, _ ->
        server.automaticPaneText = "current preview"
        click("Pane A")
        awaitText("current preview")
        awaitText("Read-only pane")
        compose.onNodeWithText("Enter").assertDoesNotExist()
        server.inputCapability = "false"
        val before = server.snapshotRequests
        compose.waitUntil(timeoutMillis = 8_000) { server.snapshotRequests >= before + 2 }
        compose.onNodeWithText("Read-only pane").assertIsDisplayed()
        compose.onNodeWithText("Enter").assertDoesNotExist()
        server.inputCapability = "\"true\""
        awaitText("Disconnected")
        assertEquals(0, server.inputCount)
    }

    @Test fun busyReadDoesNotQueueAndDraftClearsAtSelectionLifecycleAndTargetBoundaries() =
        withApp(inputCapability = "true") { server, activity ->
            click("Pane B")
            val held = server.nextRead("%2")
            activity.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            // Window protection must already be in force, even though the first preview is held.
            draftField().performTextReplacement("Android unsent marker")
            click("Insert text")
            awaitText("Bridge is busy. Try again.")
            held.reply("released read")
            awaitText("released read")
            server.automaticPaneText = "current preview"
            assertNull("a busy tap must not be queued", server.inputs.poll(2300, TimeUnit.MILLISECONDS))
            draftField().assertTextContains("Android unsent marker")

            click("Overview")
            click("Pane B")
            draftField().assert(!hasText("Android unsent marker", substring = true))
            draftField().performTextReplacement("background marker")
            activity.moveToState(Lifecycle.State.CREATED)
            activity.moveToState(Lifecycle.State.RESUMED)
            awaitText("current preview")
            draftField().assert(!hasText("background marker", substring = true))

            draftField().performTextReplacement("obsolete bridge marker")
            val oldEnter = compose.onNodeWithText("Enter").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            server.revision = "new-bridge"
            awaitText("Pane is no longer available. Select a window again.")
            click("Overview")
            click("Pane B")
            draftField().assert(!hasText("obsolete bridge marker", substring = true))
            activity.onActivity { oldEnter() }
            assertNull("a stale click cannot cross a bridge revision", server.inputs.poll(200, TimeUnit.MILLISECONDS))

            draftField().performTextReplacement("invalidated marker")
            server.secondPane = false
            awaitText("Pane is no longer available. Select a window again.")
            draftField().assertDoesNotExist()
            compose.onNodeWithText("invalidated marker").assertDoesNotExist()
            click("Overview")
            click("Pane A")
            draftField().performTextReplacement("disconnect marker")
            server.failSnapshots = true
            awaitText("Disconnected")
            server.failSnapshots = false
            awaitText("Pane A")
            click("Pane A")
            draftField().assert(!hasText("disconnect marker", substring = true))
            assertEquals(0, server.inputCount)
        }

    @Test fun lostInputAfterReselectionBlocksBothMutationsUntilReconciledAndNeverReplays() =
        withApp(inputCapability = "true") { server, _ ->
            server.automaticPaneText = "current preview"
            click("Pane A")
            awaitText("current preview")
            click("Enter")
            val lost = server.nextInput("%1")
            click("Overview")
            val open = compose.onNodeWithText("Open shell")
            open.assertIsNotEnabled().performTouchInput { click() }
            click("Pane A")
            compose.onNodeWithText("Enter").assertIsNotEnabled().performTouchInput { click() }
            val fail = CountDownLatch(1)
            server.holdFailedSnapshot = fail
            server.failSnapshots = true
            lost.reply("drop")
            try {
                awaitText("Input may have been injected. Check the pane before trying again.")
                compose.onNodeWithText("Input paused until a fresh snapshot arrives.").assertIsDisplayed()
                compose.onNodeWithText("Input injected. Check the pane for its effect.").assertDoesNotExist()
                click("Overview")
                open.assertIsNotEnabled().performTouchInput { click() }
                assertEquals(0, server.openCount)
                assertEquals(1, server.inputCount)
            } finally {
                fail.countDown()
            }
            awaitText("Disconnected")
            awaitText("Input may have been injected. Check the pane before trying again.")
            compose.onNodeWithText("Open shell may have succeeded.", substring = true).assertDoesNotExist()
            assertEquals(0, server.openCount)
            assertEquals(1, server.inputCount)

            server.failSnapshots = false
            awaitText("Pane A")
            open.assertIsEnabled()
            assertEquals(1, server.inputCount)
            assertEquals(0, server.openCount)
            click("Open shell")
            awaitText("Shell window opened.")
            assertEquals(1, server.openCount)
            assertEquals(1, server.inputCount)
        }

    @Test fun recreationDuringInputKeepsUncertaintyWithoutRestoringDraftOrSelection() =
        withApp(inputCapability = "true") { server, activity ->
            server.automaticPaneText = "current preview"
            click("Pane A")
            awaitText("current preview")
            draftField().performTextReplacement("not restorable")
            click("Enter")
            val lost = server.nextInput("%1")
            server.failSnapshots = true
            activity.recreate()
            lost.reply("drop")
            awaitText("Disconnected")
            awaitText("Input may have been injected. Check the pane before trying again.")
            assertEquals(1, server.inputCount)
            server.failSnapshots = false
            awaitText("Pane A")
            draftField().assertDoesNotExist()
            click("Pane A")
            draftField().assert(!hasText("not restorable", substring = true))
        }

    private fun draftField() = compose.onNode(hasSetTextAction() and hasText("Text to insert"))

    private fun withApp(inputCapability: String? = null, block: (PaneServer, ActivityScenario<MainActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        try {
            PaneServer(inputCapability).use { server ->
                ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    compose.onNode(hasSetTextAction() and hasText("Hostname or IP address"))
                        .performTextReplacement("127.0.0.1")
                    compose.onNode(hasSetTextAction() and hasText("Port"))
                        .performTextReplacement(server.port.toString())
                    click("Save and connect")
                    awaitText("Pane A")
                    block(server, activity)
                }
            }
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    private fun click(text: String) = compose.onNodeWithText(text).performClick()

    private fun back(activity: ActivityScenario<MainActivity>) = activity.onActivity {
        it.onBackPressedDispatcher.onBackPressed()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(conditionDescription = "text '$text' is visible", timeoutMillis = 8_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class Read(val target: JSONObject, val snapshotRequests: Int) {
        val response = LinkedBlockingQueue<String>(1)
        fun reply(text: String) { check(response.offer(text)) }
    }

    private class Input(val body: JSONObject) {
        val response = LinkedBlockingQueue<String>(1)
        fun reply(status: String) { check(response.offer(status)) }
    }

    private class PaneServer(initialCapability: String?) : AutoCloseable {
        @Volatile var inputCapability = initialCapability
        @Volatile var revision = "bridge"
        @Volatile var automaticPaneText: String? = null
        @Volatile var holdFailedSnapshot: CountDownLatch? = null
        @Volatile var inputCount = 0
        @Volatile var openCount = 0
        val inputs = LinkedBlockingQueue<Input>()
        @Volatile var secondPane = true
        @Volatile var failSnapshots = false
        @Volatile var snapshotRequests = 0
            private set
        val reads = LinkedBlockingQueue<Read>()
        private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            try {
                while (!listener.isClosed) {
                    listener.accept().use { client ->
                        client.soTimeout = 3000
                        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val request = input.readLine()
                        val headers = generateSequence { input.readLine()?.takeUnless(String::isEmpty) }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        var status = if (request.startsWith("GET ") && failSnapshots) "503 Unavailable" else "200 OK"
                        fun body(): JSONObject = JSONObject(ByteArray(headers.getValue("content-length").toInt()) {
                            input.read().also { check(it >= 0) }.toByte()
                        }.toString(Charsets.UTF_8))
                        val (type, text) = when (request) {
                            "GET /v1/connection/snapshot HTTP/1.1" -> {
                                snapshotRequests++
                                if (status.startsWith("503")) holdFailedSnapshot?.await(2, TimeUnit.SECONDS)
                                "application/json" to snapshot()
                            }
                            "POST /v1/workspace/send-input HTTP/1.1" -> {
                                assertFalse(headers.containsKey("transfer-encoding"))
                                assertEquals("application/json", headers["content-type"])
                                val action = Input(body())
                                inputCount++
                                inputs.add(action)
                                status = action.response.poll(5, TimeUnit.SECONDS) ?: "503 Fixture timeout"
                                if (status == "drop") return@use
                                "application/json" to ""
                            }
                            "POST /v1/workspace/open-shell HTTP/1.1" -> {
                                body()
                                openCount++
                                status = "204 No Content"
                                "application/json" to ""
                            }
                            "POST /v1/workspace/read-pane HTTP/1.1" -> {
                                val read = Read(body(), snapshotRequests)
                                val text = automaticPaneText ?: run {
                                    reads.add(read)
                                    read.response.poll(5, TimeUnit.SECONDS) ?: "fixture timeout"
                                }
                                "text/plain; charset=utf-8" to text
                            }
                            else -> error("Unexpected request: $request")
                        }
                        val bytes = text.toByteArray()
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes)
                        }
                    }
                }
            } catch (failure: SocketException) {
                if (!listener.isClosed) throw failure
            }
        }

        fun nextRead(pane: String): Read {
            val read = checkNotNull(reads.poll(7, TimeUnit.SECONDS)) { "No request for $pane" }
            assertEquals(pane, read.target.getString("paneId"))
            assertEquals(if (pane == "%1") "@1" else "@2", read.target.getString("windowId"))
            assertEquals("\$0", read.target.getString("sessionId"))
            assertEquals("123-456", read.target.getString("generation"))
            return read
        }

        fun nextInput(pane: String): Input {
            val action = checkNotNull(inputs.poll(7, TimeUnit.SECONDS)) { "No input request" }
            assertEquals(pane, action.body.getString("paneId"))
            assertEquals(if (pane == "%1") "@1" else "@2", action.body.getString("windowId"))
            assertEquals("\$0", action.body.getString("sessionId"))
            assertEquals("123-456", action.body.getString("generation"))
            assertEquals(5, action.body.length())
            return action
        }

        private fun snapshot(): String {
            val pane = if (secondPane) ",\"paneId\":\"%2\"" else ""
            val capability = inputCapability?.let { "\"paneInputEnabled\":$it," } ?: ""
            return """{$capability"protocolVersion":1,"revision":"$revision","workspace":{"status":"ready",
                "session":{"id":"${'$'}0","name":"work","generation":"123-456","windows":[
                {"id":"@1","name":"Pane A","paneId":"%1"},{"id":"@2","name":"Pane B"$pane}]}}}"""
        }

        override fun close() {
            listener.close()
            try {
                worker.get(7, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
