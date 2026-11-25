package dev.lhrb.app.glowkin.features.connection.adapters.outbound.endpoint

import android.content.SharedPreferences
import dev.lhrb.app.glowkin.features.connection.domain.Endpoint
import dev.lhrb.app.glowkin.features.connection.domain.InvalidEndpoint
import dev.lhrb.app.glowkin.features.connection.domain.ports.EndpointStorePort

class SharedPreferencesEndpointStore(private val preferences: SharedPreferences) : EndpointStorePort {
    override fun load(): Endpoint? {
        val host = preferences.getString("host", null) ?: return null
        return try { Endpoint.parse(host, preferences.getInt("port", 0)) } catch (_: InvalidEndpoint) { null }
    }

    override fun save(endpoint: Endpoint) {
        check(preferences.edit().putString("host", endpoint.host).putInt("port", endpoint.port).commit()) {
            "Could not save endpoint; check available device storage and retry."
        }
    }
}
