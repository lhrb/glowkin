package dev.lhrb.app.glowkin.features.connection.adapters.outbound.http

import android.util.JsonReader
import android.util.JsonToken
import dev.lhrb.app.glowkin.features.connection.domain.*
import dev.lhrb.app.glowkin.features.connection.domain.ports.SnapshotTransportPort
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

class HttpSnapshotTransport(private val timeoutMillis: Int = 3000) : SnapshotTransportPort {
    init { require(timeoutMillis > 0) }

    override fun fetch(endpoint: Endpoint): Snapshot {
        val connection = connection(endpoint, "/v1/connection/snapshot")
        try {
            connection.requestMethod = "GET"
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

    fun openShell(endpoint: Endpoint, target: SessionTarget): ChangeOutcome {
        val body = JSONObject().put("sessionId", target.id).put("generation", target.generation)
            .toString().toByteArray(Charsets.UTF_8)
        val connection = connection(endpoint, "/v1/workspace/open-shell")
        try {
            connection.postJson(body)
            return when (connection.responseCode) {
                204 -> ChangeOutcome.APPLIED
                409 -> ChangeOutcome.TARGET_UNAVAILABLE
                in 400..499 -> ChangeOutcome.REJECTED
                else -> ChangeOutcome.UNCERTAIN
            }
        } catch (_: IOException) {
            return ChangeOutcome.UNCERTAIN
        } finally {
            connection.disconnect()
        }
    }

    fun readPane(endpoint: Endpoint, target: PaneTarget): PaneRead {
        val body = JSONObject().put("sessionId", target.session.id).put("generation", target.session.generation)
            .put("windowId", target.windowId).put("paneId", target.paneId)
            .toString().toByteArray(Charsets.UTF_8)
        val connection = connection(endpoint, "/v1/workspace/read-pane")
        try {
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.postJson(body)
            return when (connection.responseCode) {
                200 -> {
                    if (connection.contentType?.substringBefore(';')?.trim() != "text/plain") return PaneRead.Failed
                    PaneRead.Captured(connection.inputStream.use { it.readBytesBounded() }.toString(Charsets.UTF_8))
                }
                409 -> PaneRead.TargetUnavailable
                else -> PaneRead.Failed
            }
        } catch (_: IOException) {
            return PaneRead.Failed
        } catch (_: ConnectionFailure) {
            return PaneRead.Failed
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.postJson(body: ByteArray) {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        // Streaming disables HttpURLConnection's transparent replay of a buffered POST.
        setFixedLengthStreamingMode(body.size)
        outputStream.use { it.write(body) }
    }

    private fun connection(endpoint: Endpoint, path: String): HttpURLConnection {
        val url = URI("http", null, endpoint.host, endpoint.port, path, null, null).toURL()
        return (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
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
                var workspace: Workspace? = null
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
                        "workspace" -> {
                            if (workspace != null) throw ConnectionFailure(ConnectionError.PROTOCOL)
                            workspace = reader.readWorkspace()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (!versionSeen || revision.isNullOrEmpty() || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw ConnectionFailure(ConnectionError.PROTOCOL)
                }
                return Snapshot(revision, workspace)
            }
        } catch (failure: ConnectionFailure) {
            throw failure
        } catch (_: IOException) {
            throw ConnectionFailure(ConnectionError.PROTOCOL)
        } catch (_: IllegalStateException) {
            throw ConnectionFailure(ConnectionError.PROTOCOL)
        }
    }

    private fun JsonReader.readWorkspace(): Workspace {
        var status: String? = null
        var session: Workspace.Ready? = null
        readFields { field ->
            when (field) {
                "status" -> status = readString()
                "session" -> session = readSession()
                else -> skipValue()
            }
        }
        return when (status) {
            "ready" -> session ?: throw ConnectionFailure(ConnectionError.PROTOCOL)
            "no_session" -> Workspace.NoSession
            "multiple_sessions" -> Workspace.MultipleSessions
            "unavailable" -> Workspace.Unavailable
            else -> throw ConnectionFailure(ConnectionError.PROTOCOL)
        }
    }

    private fun JsonReader.readSession(): Workspace.Ready {
        var id: String? = null
        var generation: String? = null
        var name: String? = null
        var windows: List<WorkspaceWindow>? = null
        readFields { field ->
            when (field) {
                "id" -> id = readString()
                "generation" -> generation = readString()
                "name" -> name = readString()
                "windows" -> {
                    beginArray()
                    windows = buildList { while (hasNext()) add(readWindow()) }
                    endArray()
                }
                else -> skipValue()
            }
        }
        return Workspace.Ready(SessionTarget(requireId(id), requireId(generation)), requireString(name),
            windows ?: throw ConnectionFailure(ConnectionError.PROTOCOL))
    }

    private fun JsonReader.readWindow(): WorkspaceWindow {
        var id: String? = null
        var name: String? = null
        var paneId: String? = null
        readFields { field ->
            when (field) {
                "id" -> id = readString()
                "name" -> name = readString()
                "paneId" -> paneId = requireId(readString())
                else -> skipValue()
            }
        }
        return WorkspaceWindow(requireId(id), requireString(name), paneId)
    }

    private inline fun JsonReader.readFields(read: (String) -> Unit) {
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            val field = nextName()
            if (!seen.add(field)) throw ConnectionFailure(ConnectionError.PROTOCOL)
            read(field)
        }
        endObject()
    }

    private fun JsonReader.readString(): String {
        if (peek() != JsonToken.STRING) throw ConnectionFailure(ConnectionError.PROTOCOL)
        return nextString()
    }

    private fun requireString(value: String?): String = value ?: throw ConnectionFailure(ConnectionError.PROTOCOL)

    private fun requireId(value: String?): String =
        value?.takeIf { it.isNotEmpty() } ?: throw ConnectionFailure(ConnectionError.PROTOCOL)
}
