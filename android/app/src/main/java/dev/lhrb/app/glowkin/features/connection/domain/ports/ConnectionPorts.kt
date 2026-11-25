package dev.lhrb.app.glowkin.features.connection.domain.ports

import dev.lhrb.app.glowkin.features.connection.domain.Endpoint
import dev.lhrb.app.glowkin.features.connection.domain.Snapshot

interface EndpointStorePort {
    fun load(): Endpoint?
    fun save(endpoint: Endpoint)
}

interface SnapshotTransportPort {
    fun fetch(endpoint: Endpoint): Snapshot
}
