package dev.lhrb.app.glowkin.features.connection.domain

import dev.lhrb.app.glowkin.features.connection.domain.ports.EndpointStorePort
import dev.lhrb.app.glowkin.features.connection.domain.ports.SnapshotTransportPort
import org.junit.Assert.*
import org.junit.Test

class ConnectionUseCaseTest {
    @Test fun `accepts DNS WLAN Tailscale IPv4 and IPv6 with boundary ports`() {
        for (host in listOf("pc", "pc.home", "pc.tail123.ts.net", "192.168.1.10", "100.101.102.103", "::1", "fd7a:115c:a1e0::1234", "[::1]")) {
            for (port in listOf(1, 65535)) {
                val endpoint = Endpoint.parse(host, port)
                assertEquals("host=$host", host.removePrefix("[").removeSuffix("]"), endpoint.host)
                assertEquals(port, endpoint.port)
            }
        }
    }

    @Test fun `rejects URLs malformed addresses and invalid ports without replacing saved endpoint`() {
        val saved = Endpoint.parse("pc.home", 8787)
        val store = MemoryStore(saved)
        val transport = FakeTransport()
        val connection = ConnectionUseCase(store, transport)
        for (host in listOf("", " ", "http://pc", "pc/path", "pc?x=1", "user@pc", "pc:8787", "999.1.1.1", "192.168.1", "bad host", "-pc.home", "pc..home", "[::1", "::gg", "[pc]")) {
            assertInvalid { connection.saveEndpoint(host, 8787) }
            assertEquals("rejected host=$host", saved, store.endpoint)
        }
        for (port in listOf(-1, 0, 65536)) {
            assertInvalid { connection.saveEndpoint("pc", port) }
            assertEquals(saved, store.endpoint)
        }
        assertTrue(transport.requested.isEmpty())
    }

    @Test fun `saves replaces and reloads exactly one endpoint without connecting on recreation`() {
        val store = MemoryStore()
        val transport = FakeTransport()
        val first = ConnectionUseCase(store, transport)
        first.saveEndpoint("pc.home", 8787)
        first.connect()
        first.saveEndpoint("100.101.102.103", 9000)
        val replacement = Endpoint.parse("100.101.102.103", 9000)
        assertEquals(replacement, store.endpoint)
        assertEquals(replacement, first.state.endpoint)
        assertEquals(ConnectionStatus.DISCONNECTED, first.state.status)
        assertNull("old server data must not appear under the replacement", first.state.snapshot)
        val recreated = ConnectionUseCase(store, transport)
        assertEquals(replacement, recreated.state.endpoint)
        assertEquals(ConnectionStatus.DISCONNECTED, recreated.state.status)
        assertEquals(1, transport.requested.size)
    }

    @Test fun `connect exposes connecting until authoritative snapshot arrives and disconnect retains stale data`() {
        val transport = FakeTransport()
        val connection = ConnectionUseCase(MemoryStore(Endpoint.parse("pc", 8787)), transport)
        assertEquals(ConnectionStatus.DISCONNECTED, connection.state.status)
        transport.fetch = {
            assertEquals(ConnectionStatus.CONNECTING, connection.state.status)
            Snapshot("before-restart")
        }
        connection.connect()
        assertEquals(ConnectionStatus.CONNECTED, connection.state.status)
        assertEquals(Snapshot("before-restart"), connection.state.snapshot)
        assertFalse(connection.state.stale)
        connection.disconnect()
        assertEquals(ConnectionStatus.DISCONNECTED, connection.state.status)
        assertEquals(Snapshot("before-restart"), connection.state.snapshot)
        assertTrue(connection.state.stale)
        assertEquals("disconnect performs no remote action", 1, transport.requested.size)
    }

    @Test fun `failed polling preserves endpoint and stale snapshot then reconnect replaces rather than merges`() {
        val endpoint = Endpoint.parse("pc", 8787)
        val store = MemoryStore(endpoint)
        val transport = FakeTransport()
        val connection = ConnectionUseCase(store, transport)
        connection.connect()
        transport.fetch = { throw ConnectionFailure(ConnectionError.UNREACHABLE) }
        connection.poll()
        assertEquals(ConnectionStatus.DISCONNECTED, connection.state.status)
        assertEquals(ConnectionError.UNREACHABLE, connection.state.error)
        assertEquals(endpoint, connection.state.endpoint)
        assertEquals(endpoint, store.endpoint)
        assertEquals(Snapshot("initial"), connection.state.snapshot)
        assertTrue(connection.state.stale)
        transport.fetch = { Snapshot("new-server-revision") }
        connection.connect()
        assertEquals(ConnectionStatus.CONNECTED, connection.state.status)
        assertEquals(Snapshot("new-server-revision"), connection.state.snapshot)
        assertFalse(connection.state.stale)
        assertNull(connection.state.error)
        transport.fetch = { Snapshot("next-poll") }
        connection.poll()
        assertEquals(Snapshot("next-poll"), connection.state.snapshot)
    }

    @Test fun `initial unreachable or incompatible bridge never becomes connected`() {
        for (error in listOf(ConnectionError.UNREACHABLE, ConnectionError.PROTOCOL)) {
            val endpoint = Endpoint.parse("pc", 8787)
            val transport = FakeTransport().apply { fetch = { throw ConnectionFailure(error) } }
            val connection = ConnectionUseCase(MemoryStore(endpoint), transport)
            connection.connect()
            assertEquals(ConnectionStatus.DISCONNECTED, connection.state.status)
            assertEquals(error, connection.state.error)
            assertEquals(endpoint, connection.state.endpoint)
            assertNull(connection.state.snapshot)
        }
    }

    @Test fun `uncertain change is reported never replayed and blocks another attempt until reconciliation`() {
        val transport = FakeTransport()
        val connection = ConnectionUseCase(MemoryStore(Endpoint.parse("pc", 8787)), transport)
        var sends = 0
        val send = { sends++; ChangeOutcome.UNCERTAIN }
        assertEquals(ChangeOutcome.NOT_CONNECTED, connection.attemptChange(send))
        assertEquals(0, sends)
        connection.connect()
        assertEquals(ChangeOutcome.UNCERTAIN, connection.attemptChange(send))
        assertTrue(connection.state.needsReconciliation)
        assertEquals(ChangeOutcome.RECONCILIATION_REQUIRED, connection.attemptChange(send))
        transport.fetch = { throw ConnectionFailure(ConnectionError.UNREACHABLE) }
        connection.poll()
        connection.connect()
        assertTrue(connection.state.needsReconciliation)
        assertEquals(1, sends)
        transport.fetch = { Snapshot("authoritative-after-delivery") }
        connection.connect()
        connection.poll()
        assertFalse(connection.state.needsReconciliation)
        assertEquals("polling and reconnect must never invoke the previous change", 1, sends)
        assertEquals(ChangeOutcome.APPLIED, connection.attemptChange { sends++; ChangeOutcome.APPLIED })
        assertEquals(2, sends)
    }

    @Test fun `transport failure after change invocation is uncertain and never replayed`() {
        val transport = FakeTransport()
        val connection = ConnectionUseCase(MemoryStore(Endpoint.parse("pc", 8787)), transport)
        connection.connect()
        var sends = 0
        assertEquals(ChangeOutcome.UNCERTAIN, connection.attemptChange {
            sends++
            throw ConnectionFailure(ConnectionError.UNREACHABLE)
        })
        assertTrue(connection.state.needsReconciliation)
        assertEquals(ChangeOutcome.RECONCILIATION_REQUIRED, connection.attemptChange {
            sends++
            ChangeOutcome.APPLIED
        })
        connection.disconnect()
        assertTrue(connection.state.stale)
        connection.connect()
        connection.poll()
        assertFalse(connection.state.needsReconciliation)
        assertEquals("reconciliation must not replay a possibly delivered change", 1, sends)
    }

    @Test fun `in-flight snapshot cannot reconnect after local disconnect`() {
        val endpoint = Endpoint.parse("pc", 8787)
        val transport = FakeTransport()
        val connection = ConnectionUseCase(MemoryStore(endpoint), transport)
        connection.connect()
        transport.fetch = {
            connection.disconnect()
            Snapshot("arrived-after-disconnect")
        }

        connection.poll()

        assertEquals(ConnectionStatus.DISCONNECTED, connection.state.status)
        assertEquals(endpoint, connection.state.endpoint)
        assertEquals(Snapshot("initial"), connection.state.snapshot)
        assertTrue("retained data must stay stale after disconnect", connection.state.stale)
    }

    @Test fun `in-flight old-server snapshot cannot populate or reconnect a replacement endpoint`() {
        val store = MemoryStore(Endpoint.parse("old-pc", 8787))
        val transport = FakeTransport()
        val connection = ConnectionUseCase(store, transport)
        val replacement = Endpoint.parse("new-pc", 9000)
        transport.fetch = {
            connection.saveEndpoint(replacement.host, replacement.port)
            Snapshot("old-server-response")
        }

        connection.connect()

        assertEquals(replacement, store.endpoint)
        assertEquals(replacement, connection.state.endpoint)
        assertEquals(ConnectionStatus.DISCONNECTED, connection.state.status)
        assertNull("old-server data must not appear under the replacement", connection.state.snapshot)
    }

    @Test fun `disconnect during change delivery cannot discard subsequent uncertainty`() {
        val endpoint = Endpoint.parse("pc", 8787)
        val transport = FakeTransport()
        val connection = ConnectionUseCase(MemoryStore(endpoint), transport)
        connection.connect()
        var sends = 0

        assertEquals(ChangeOutcome.UNCERTAIN, connection.attemptChange {
            sends++
            connection.disconnect()
            ChangeOutcome.UNCERTAIN
        })

        assertEquals(endpoint, connection.state.endpoint)
        assertEquals(ConnectionStatus.DISCONNECTED, connection.state.status)
        assertTrue("disconnect must not discard uncertain delivery for the same endpoint", connection.state.needsReconciliation)
        assertEquals(ChangeOutcome.RECONCILIATION_REQUIRED, connection.attemptChange {
            sends++
            ChangeOutcome.APPLIED
        })
        assertEquals(1, sends)
        connection.connect()
        assertFalse(connection.state.needsReconciliation)
        assertEquals("reconnect must not replay the uncertain change", 1, sends)
    }

    @Test fun `snapshot received during delivery cannot reconcile a subsequently uncertain outcome`() {
        val endpoint = Endpoint.parse("pc", 8787)
        val transport = FakeTransport()
        val connection = ConnectionUseCase(MemoryStore(endpoint), transport)
        connection.connect()
        transport.fetch = { Snapshot("before-uncertain-outcome") }
        var sends = 0

        assertEquals(ChangeOutcome.UNCERTAIN, connection.attemptChange {
            sends++
            connection.poll()
            ChangeOutcome.UNCERTAIN
        })

        assertEquals(endpoint, connection.state.endpoint)
        assertEquals(Snapshot("before-uncertain-outcome"), connection.state.snapshot)
        assertTrue("a preceding snapshot cannot reconcile a later uncertain outcome", connection.state.needsReconciliation)
        assertEquals(ChangeOutcome.RECONCILIATION_REQUIRED, connection.attemptChange {
            sends++
            ChangeOutcome.APPLIED
        })
        assertEquals(1, sends)
        transport.fetch = { Snapshot("after-uncertain-outcome") }
        connection.poll()
        assertFalse(connection.state.needsReconciliation)
        assertEquals("reconciliation must not replay the uncertain change", 1, sends)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected InvalidEndpoint")
        } catch (_: InvalidEndpoint) { }
    }

    private class MemoryStore(var endpoint: Endpoint? = null) : EndpointStorePort {
        override fun load(): Endpoint? = endpoint
        override fun save(endpoint: Endpoint) { this.endpoint = endpoint }
    }

    private class FakeTransport : SnapshotTransportPort {
        val requested = mutableListOf<Endpoint>()
        var fetch: () -> Snapshot = { Snapshot("initial") }
        override fun fetch(endpoint: Endpoint): Snapshot {
            requested += endpoint
            return fetch()
        }
    }
}
