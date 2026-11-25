package dev.lhrb.app.glowkin.features.connection.adapters

import androidx.test.platform.app.InstrumentationRegistry
import dev.lhrb.app.glowkin.features.connection.adapters.outbound.endpoint.SharedPreferencesEndpointStore
import dev.lhrb.app.glowkin.features.connection.adapters.outbound.http.HttpSnapshotTransport
import dev.lhrb.app.glowkin.features.connection.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectionAdaptersTest {
    @Test fun endpointSurvivesAdapterRecreationAndReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("connection-adapter-test", 0)
        preferences.edit().clear().commit()
        try {
            assertNull(SharedPreferencesEndpointStore(preferences).load())
            val first = Endpoint.parse("pc.tail123.ts.net", 8787)
            SharedPreferencesEndpointStore(preferences).save(first)
            assertEquals(first, SharedPreferencesEndpointStore(preferences).load())
            val replacement = Endpoint.parse("fd7a:115c:a1e0::1234", 65535)
            SharedPreferencesEndpointStore(preferences).save(replacement)
            assertEquals(replacement, SharedPreferencesEndpointStore(preferences).load())
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun httpAdapterFetchesAnonymousAuthoritativeSnapshotsWithoutCaching() {
        val requests = mutableListOf<String>()
        serve(listOf(200 to """{"protocolVersion":1,"revision":"one"}""",
                     200 to """{"protocolVersion":1,"revision":"after-restart","futureField":true}"""), requests) { endpoint ->
            val transport = HttpSnapshotTransport(timeoutMillis = 1000)
            assertEquals(Snapshot("one"), transport.fetch(endpoint))
            assertEquals(Snapshot("after-restart"), transport.fetch(endpoint))
        }
        assertEquals(2, requests.size)
        for (request in requests) {
            assertEquals("GET /v1/connection/snapshot HTTP/1.1", request.lineSequence().first())
            assertFalse(request.contains("Authorization:", ignoreCase = true))
            assertFalse(request.contains("Cookie:", ignoreCase = true))
        }
    }

    @Test fun invalidOrIncompatibleSnapshotsNeverCountAsConnected() {
        for ((status, body) in listOf(
            200 to "not json",
            200 to "{}",
            200 to """{"protocolVersion":2,"revision":"r"}""",
            200 to """{"protocolVersion":"1","revision":"r"}""",
            200 to """{"protocolVersion":1.5,"revision":"r"}""",
            200 to """{"protocolVersion":1,"revision":""}""",
            200 to """{"protocolVersion":1,"revision":17}""",
            200 to """{"protocolVersion":1,"revision":null}""",
            404 to """{"protocolVersion":1,"revision":"r"}"""
        )) {
            serve(listOf(status to body)) { endpoint ->
                expectFailure(ConnectionError.PROTOCOL) {
                    HttpSnapshotTransport(timeoutMillis = 1000).fetch(endpoint)
                }
            }
        }
    }

    @Test fun paneAdapterPreservesPlainTextAndBoundsStreamedBodies() {
        val exactLimit = "x".repeat(65536)
        val requests = mutableListOf<String>()
        val target = PaneTarget(SessionTarget("\$0", "123-456"), "@1", "%2")
        serve(listOf(200 to "", 200 to "one\n    two\n", 200 to exactLimit, 200 to "$exactLimit!", 409 to ""),
            requests, contentType = "text/plain; charset=utf-8", chunked = true) { endpoint ->
            val transport = HttpSnapshotTransport(timeoutMillis = 1500)
            assertEquals(PaneRead.Captured(""), transport.readPane(endpoint, target))
            assertEquals(PaneRead.Captured("one\n    two\n"), transport.readPane(endpoint, target))
            assertEquals(PaneRead.Captured(exactLimit), transport.readPane(endpoint, target))
            assertEquals(PaneRead.Failed, transport.readPane(endpoint, target))
            assertEquals(PaneRead.TargetUnavailable, transport.readPane(endpoint, target))
        }
        assertEquals(5, requests.size)
        for (request in requests) {
            assertEquals("POST /v1/workspace/read-pane HTTP/1.1", request.lineSequence().first())
            assertTrue(request.contains("Accept: text/plain", ignoreCase = true))
            assertTrue(request.contains("Cache-Control: no-cache, no-store", ignoreCase = true))
        }
    }

    @Test fun refusedConnectionMapsToActionableUnreachableError() {
        val port = ServerSocket(0).use { it.localPort }
        expectFailure(ConnectionError.UNREACHABLE) {
            HttpSnapshotTransport(timeoutMillis = 500).fetch(Endpoint.parse("127.0.0.1", port))
        }
    }

    private fun expectFailure(expected: ConnectionError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (failure: ConnectionFailure) {
            assertEquals(expected, failure.error)
        }
    }

    /** A real socket fixture; no mocked HTTP or Android networking methods. */
    private fun serve(
        responses: List<Pair<Int, String>>,
        requests: MutableList<String> = mutableListOf(),
        contentType: String = "application/json",
        chunked: Boolean = false,
        block: (Endpoint) -> Unit
    ) {
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            val worker = executor.submit {
                for ((status, body) in responses) {
                    server.accept().use { client ->
                        client.soTimeout = 3000
                        val reader = client.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            lines += line
                        }
                        requests += lines.joinToString("\r\n")
                        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        repeat(length) { check(reader.read() >= 0) } // Fixture POST bodies are ASCII.
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val framing = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: ${bytes.size}"
                        val header = "HTTP/1.1 $status Response\r\nContent-Type: $contentType\r\n$framing\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply {
                            write(header.toByteArray(Charsets.US_ASCII))
                            if (chunked) {
                                for (offset in bytes.indices step 4096) {
                                    val size = minOf(4096, bytes.size - offset)
                                    write("${size.toString(16)}\r\n".toByteArray())
                                    write(bytes, offset, size)
                                    write("\r\n".toByteArray())
                                }
                                write("0\r\n\r\n".toByteArray())
                            } else write(bytes)
                            flush()
                        }
                    }
                }
            }
            try {
                block(Endpoint.parse("127.0.0.1", server.localPort))
                worker.get(5, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }
}
