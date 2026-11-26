package dev.lhrb.app.glowkin.features.connection

import android.content.Context
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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PanePreviewTest {
    @get:Rule val compose = createEmptyComposeRule()

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

    private fun withApp(block: (PaneServer, ActivityScenario<MainActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        try {
            PaneServer().use { server ->
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

    private class Read(val target: JSONObject) {
        val response = LinkedBlockingQueue<String>(1)
        fun reply(text: String) { check(response.offer(text)) }
    }

    private class PaneServer : AutoCloseable {
        @Volatile var secondPane = true
        @Volatile var failSnapshots = false
        val reads = LinkedBlockingQueue<Read>()
        private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            try {
                while (!listener.isClosed) {
                    listener.accept().use { client ->
                        client.soTimeout = 3000
                        val input = client.getInputStream().bufferedReader()
                        val request = input.readLine()
                        val headers = generateSequence { input.readLine()?.takeUnless(String::isEmpty) }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        val status = if (request.startsWith("GET ") && failSnapshots) "503 Unavailable" else "200 OK"
                        val (type, text) = when (request) {
                            "GET /v1/connection/snapshot HTTP/1.1" -> "application/json" to snapshot()
                            "POST /v1/workspace/read-pane HTTP/1.1" -> {
                                val body = buildString {
                                    repeat(headers.getValue("content-length").toInt()) { append(input.read().toChar()) }
                                }
                                val read = Read(JSONObject(body))
                                reads.add(read)
                                "text/plain; charset=utf-8" to (read.response.poll(5, TimeUnit.SECONDS) ?: "fixture timeout")
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

        private fun snapshot(): String {
            val pane = if (secondPane) ",\"paneId\":\"%2\"" else ""
            return """{"protocolVersion":1,"revision":"bridge","workspace":{"status":"ready",
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
