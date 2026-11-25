package dev.lhrb.app.glowkin.features.connection

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.lhrb.app.glowkin.MainActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lostOpenResponseIsNotReplayedAndManualRetryUsesReconciledSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        try {
            WorkspaceServer().use { server ->
                ActivityScenario.launch(MainActivity::class.java).use {
                    compose.onNode(hasSetTextAction() and hasText("Hostname or IP address"))
                        .performTextReplacement("127.0.0.1")
                    compose.onNode(hasSetTextAction() and hasText("Port"))
                        .performTextReplacement(server.port.toString())
                    click("Save and connect")
                    awaitWindow("Preexisting editor")

                    server.pcWindow = true
                    click("Refresh")
                    awaitWindow("PC terminal")

                    click("Open shell")
                    compose.waitUntil(timeoutMillis = 10_000) { server.failedReads.get() > 0 }
                    assertEquals(listOf(Target("\$0", "first-generation")), server.opens.toList())

                    // A visible and enabled action may request a refresh, but cannot send a stale POST.
                    val enabledOpen = hasText("Open shell") and hasClickAction() and isEnabled()
                    val readsBefore = server.failedReads.get()
                    if (compose.onAllNodes(enabledOpen).fetchSemanticsNodes().isNotEmpty()) {
                        compose.onNode(enabledOpen).performScrollTo().performClick()
                    }
                    compose.waitUntil(timeoutMillis = 10_000) { server.failedReads.get() > readsBefore }
                    assertEquals("no POST before reconciliation", 1, server.opens.size)

                    server.reachable = true
                    awaitWindow("Recovered terminal")
                    assertEquals("reconciliation must not replay POST", 1, server.opens.size)
                    click("Open shell")
                    awaitWindow("Manually opened terminal")
                    assertEquals(
                        listOf(Target("\$0", "first-generation"), Target("\$1", "recovered-generation")),
                        server.opens.toList(),
                    )
                }
            }
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun awaitWindow(name: String) {
        compose.waitUntil(conditionDescription = "window '$name' is listed", timeoutMillis = 10_000) {
            compose.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private data class Target(val sessionId: String, val generation: String)

    private class WorkspaceServer : AutoCloseable {
        @Volatile var pcWindow = false
        @Volatile var reachable = true
        val failedReads = AtomicInteger()
        val opens = CopyOnWriteArrayList<Target>()
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
                        val headers = buildMap {
                            while (true) {
                                val line = reader.readLine()?.takeUnless { it.isEmpty() } ?: break
                                put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
                            }
                        }
                        val (status, body) = when (request) {
                            "GET /v1/connection/snapshot HTTP/1.1" -> if (reachable) {
                                "200 OK" to snapshot()
                            } else {
                                failedReads.incrementAndGet()
                                "503 Service Unavailable" to ""
                            }
                            "POST /v1/workspace/open-shell HTTP/1.1" -> {
                                check(headers["content-type"]?.substringBefore(';') == "application/json")
                                val json = JSONObject(reader.readBody(headers))
                                val target = Target(json.getString("sessionId"), json.getString("generation"))
                                val firstOpen = opens.isEmpty()
                                opens.add(target)
                                if (firstOpen) {
                                    reachable = false
                                    // The bridge received the request, but the client sees no response.
                                    return@use
                                }
                                "204 No Content" to ""
                            }
                            else -> error("Unexpected request: $request")
                        }
                        val payload = body.toByteArray()
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(payload)
                            flush()
                        }
                    }
                }
            } catch (failure: SocketException) {
                if (!server.isClosed) throw failure
            }
        }

        private fun snapshot(): String {
            val (id, generation, windows) = if (opens.isEmpty()) {
                Triple("\$0", "first-generation", buildList {
                    add("Preexisting editor")
                    if (pcWindow) add("PC terminal")
                })
            } else {
                Triple("\$1", "recovered-generation", buildList {
                    add("Recovered terminal")
                    if (opens.size > 1) add("Manually opened terminal")
                })
            }
            val inventory = windows.mapIndexed { index, name -> """{"id":"@$index","name":"$name"}""" }
            return """{"protocolVersion":1,"revision":"bridge","workspace":{"status":"ready",
                "session":{"id":"$id","name":"work","generation":"$generation",
                "windows":[${inventory.joinToString()}]}}}"""
        }

        private fun BufferedReader.readBody(headers: Map<String, String>): String {
            // The two fixture values and all JSON syntax are ASCII.
            fun readAscii(length: Int): String = buildString(length) {
                repeat(length) {
                    val value = read()
                    check(value >= 0) { "Incomplete POST body" }
                    append(value.toChar())
                }
            }
            if (headers["transfer-encoding"].equals("chunked", ignoreCase = true)) {
                return buildString {
                    while (true) {
                        val size = readLine().substringBefore(';').toInt(16)
                        if (size == 0) break
                        append(readAscii(size))
                        check(readLine() == "")
                    }
                }
            }
            return readAscii(headers.getValue("content-length").toInt())
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
