package dev.lhrb.app.glowkin.features.connection.adapters.outbound.http

import android.util.JsonReader
import android.util.JsonToken
import dev.lhrb.app.glowkin.features.connection.domain.*
import dev.lhrb.app.glowkin.features.connection.domain.ports.SnapshotTransportPort
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

class HttpSnapshotTransport(private val timeoutMillis: Int = 3000) : SnapshotTransportPort {
    init { require(timeoutMillis > 0) }

    override fun fetch(endpoint: Endpoint): Snapshot {
        val url = URI("http", null, endpoint.host, endpoint.port, "/v1/connection/snapshot", null, null).toURL()
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            if (connection.responseCode != 200) throw ConnectionFailure(ConnectionError.PROTOCOL)
            if (connection.contentType?.substringBefore(';')?.trim() != "application/json") {
                throw ConnectionFailure(ConnectionError.PROTOCOL)
            }
            val bytes = connection.inputStream.use { it.readBytesBounded() }
            return parse(bytes.toString(Charsets.UTF_8))
        } catch (failure: ConnectionFailure) {
            throw failure
        } catch (_: IOException) {
            throw ConnectionFailure(ConnectionError.UNREACHABLE)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val size = read(buffer)
            if (size < 0) return output.toByteArray()
            if (output.size() + size > 65536) throw ConnectionFailure(ConnectionError.PROTOCOL)
            output.write(buffer, 0, size)
        }
    }

    private fun parse(body: String): Snapshot {
        try {
            JsonReader(StringReader(body)).use { reader ->
                reader.isLenient = false
                var versionSeen = false
                var revision: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "protocolVersion" -> {
                            if (versionSeen || reader.peek() != JsonToken.NUMBER || reader.nextString() != "1") {
                                throw ConnectionFailure(ConnectionError.PROTOCOL)
                            }
                            versionSeen = true
                        }
                        "revision" -> {
                            if (revision != null || reader.peek() != JsonToken.STRING) throw ConnectionFailure(ConnectionError.PROTOCOL)
                            revision = reader.nextString()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (!versionSeen || revision.isNullOrEmpty() || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw ConnectionFailure(ConnectionError.PROTOCOL)
                }
                return Snapshot(revision)
            }
        } catch (failure: ConnectionFailure) {
            throw failure
        } catch (_: IOException) {
            throw ConnectionFailure(ConnectionError.PROTOCOL)
        } catch (_: IllegalStateException) {
            throw ConnectionFailure(ConnectionError.PROTOCOL)
        }
    }
}
