package dev.lhrb.app.glowkin.features.connection

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.lhrb.app.glowkin.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConnectionScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedEndpointConnectsDisconnectsAndRefreshesAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        try {
            SnapshotServer().use { server ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    compose.onNode(hasSetTextAction() and hasText("Hostname or IP address"))
                        .performTextReplacement("127.0.0.1")
                    compose.onNode(hasSetTextAction() and hasText("Port"))
                        .performTextReplacement(server.port.toString())
                    compose.onNodeWithText("Save and connect").performScrollTo().performClick()
                    awaitText("Connected")
                    awaitText("Bridge revision: first")
                    val savedEndpoint = "Saved endpoint: 127.0.0.1 · port ${server.port}"
                    compose.onNodeWithText(savedEndpoint).assertExists()

                    compose.onNodeWithText("Disconnect").performScrollTo().performClick()
                    awaitText("Disconnected")
                    awaitText("Stale snapshot — not current PC state")
                    compose.onNodeWithText("Bridge revision: first").assertExists()

                    server.revision.set("after-recreation")
                    scenario.recreate()

                    awaitText("Connected")
                    awaitText("Bridge revision: after-recreation")
                    compose.onNodeWithText(savedEndpoint).assertExists()
                    compose.onNode(hasSetTextAction() and hasText("Hostname or IP address"))
                        .assertTextContains("127.0.0.1")
                    compose.onNode(hasSetTextAction() and hasText("Port"))
                        .assertTextContains(server.port.toString())
                    compose.onNodeWithText("Current bridge snapshot").assertExists()
                    compose.onNodeWithText("Stale snapshot — not current PC state").assertDoesNotExist()
                }
            }
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class SnapshotServer : AutoCloseable {
        val revision = AtomicReference("first")
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use { client ->
                        client.soTimeout = 3000
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        check(request == "GET /v1/connection/snapshot HTTP/1.1") { "Unexpected request: $request" }
                        val body = """{"protocolVersion":1,"revision":"${revision.get()}"}""".toByteArray()
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            } catch (failure: SocketException) {
                if (!server.isClosed) throw failure
            }
        }

        override fun close() {
            server.close()
            try {
                worker.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue("HTTP fixture did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
