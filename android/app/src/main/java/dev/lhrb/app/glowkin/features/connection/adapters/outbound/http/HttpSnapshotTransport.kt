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
        return mutate(endpoint, "/v1/workspace/open-shell", body)
    }

    fun sendInput(endpoint: Endpoint, target: PaneTarget, input: PaneInput): ChangeOutcome {
        val variant = when (input) {
            is PaneInput.Text -> JSONObject().put("text", input.value)
            is PaneInput.Key -> JSONObject().put("key", input.wireName)
        }
        return mutate(endpoint, "/v1/workspace/send-input", paneBody(target).put("input", variant))
    }

    private fun mutate(endpoint: Endpoint, path: String, body: JSONObject): ChangeOutcome {
        val connection = connection(endpoint, path)
        try {
            connection.postJson(body.toString().toByteArray(Charsets.UTF_8))
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

    private fun paneBody(target: PaneTarget): JSONObject =
        JSONObject().put("sessionId", target.session.id).put("generation", target.session.generation)
            .put("windowId", target.windowId).put("paneId", target.paneId)

    fun readPane(endpoint: Endpoint, target: PaneTarget): PaneRead {
        val body = paneBody(target).toString().toByteArray(Charsets.UTF_8)
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

    internal fun readPiChat(endpoint: Endpoint, target: PaneTarget, instanceId: String?): PiChatRead {
        val body = paneBody(target).put("instanceId", instanceId).toString().toByteArray(Charsets.UTF_8)
        val connection = connection(endpoint, "/v1/workspace/read-pi-chat")
        try {
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.postJson(body)
            return when (connection.responseCode) {
                200 -> {
                    if (connection.contentType?.substringBefore(';')?.trim() != "application/json") return PiChatRead.Unavailable
                    val bytes = connection.inputStream.use { it.readBytesBounded(256 * 1024) }
                    PiChatRead.Available(parsePiChat(bytes.decodeToString(throwOnInvalidSequence = true)))
                }
                404 -> PiChatRead.Unsupported
                409 -> PiChatRead.Conflict
                410 -> PiChatRead.Exited
                else -> PiChatRead.Unavailable
            }
        } catch (_: IOException) {
            return PiChatRead.Unavailable
        } catch (_: ConnectionFailure) {
            return PiChatRead.Unavailable
        } catch (_: IllegalStateException) {
            return PiChatRead.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    internal fun editPiDraft(endpoint: Endpoint, target: PaneTarget, bridgeRevision: String, edit: PiDraftEdit): ChangeOutcome =
        mutate(endpoint, "/v1/workspace/edit-pi-draft", paneBody(target)
            .put("instanceId", edit.basis.instanceId).put("draftRevision", edit.basis.revision)
            .put("text", edit.text).put("bridgeRevision", bridgeRevision))

    private fun parsePiChat(body: String): PiChatSnapshot = JsonReader(StringReader(body)).use { reader ->
        reader.isLenient = false
        var instanceId: String? = null
        var revision: String? = null
        var draft: String? = null
        var draftSeen = false
        var messages: List<ChatMessage>? = null
        var subagents: List<String>? = null
        var subagentsSeen = false
        var activity: PiActivity? = null
        var queued: Boolean? = null
        var inputEnabled: Boolean? = null
        reader.readFields { field ->
            when (field) {
                "instanceId" -> instanceId = reader.readString()
                "draftRevision" -> revision = reader.readString()
                "draft" -> {
                    draftSeen = true
                    draft = reader.readNullable { readString() }
                    if (draft?.let(::isValidPiDraft) == false) invalidProtocol()
                }
                "messages" -> messages = reader.readList { readChatMessage() }
                "subagents" -> {
                    subagentsSeen = true
                    subagents = reader.readNullable { readList { readString() } }
                }
                "activity" -> activity = when (reader.readString()) {
                    "idle" -> PiActivity.IDLE
                    "working" -> PiActivity.WORKING
                    "compacting" -> PiActivity.COMPACTING
                    "respond_on_pc" -> PiActivity.RESPOND_ON_PC
                    else -> invalidProtocol()
                }
                "queued" -> queued = reader.nextBoolean()
                "inputEnabled" -> inputEnabled = reader.nextBoolean()
                else -> reader.skipValue()
            }
        }
        if (!draftSeen || !subagentsSeen || reader.peek() != JsonToken.END_DOCUMENT) invalidProtocol()
        val conversation = messages ?: invalidProtocol()
        if (conversation.distinctBy { it.id }.size != conversation.size) invalidProtocol()
        PiChatSnapshot(requireId(instanceId), requireId(revision), draft, conversation, subagents,
            activity ?: invalidProtocol(), queued ?: invalidProtocol(), inputEnabled ?: invalidProtocol())
    }

    private fun JsonReader.readChatMessage(): ChatMessage {
        var id: String? = null
        var role: ChatRole? = null
        var text: String? = null
        var tool: String? = null
        readFields { field ->
            when (field) {
                "id" -> id = readString()
                "role" -> role = when (readString()) {
                    "user" -> ChatRole.USER
                    "assistant" -> ChatRole.ASSISTANT
                    "tool" -> ChatRole.TOOL
                    else -> invalidProtocol()
                }
                "text" -> text = readString()
                "tool" -> tool = readNullable { readString() }
                else -> skipValue()
            }
        }
        return ChatMessage(requireId(id), role ?: invalidProtocol(), requireString(text), tool)
    }

    private inline fun <T> JsonReader.readNullable(read: JsonReader.() -> T): T? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else read(this)

    private inline fun <T> JsonReader.readList(read: JsonReader.() -> T): List<T> {
        beginArray()
        val items = buildList { while (hasNext()) add(read(this@readList)) }
        endArray()
        return items
    }

    private fun invalidProtocol(): Nothing = throw ConnectionFailure(ConnectionError.PROTOCOL)

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

    private fun java.io.InputStream.readBytesBounded(limit: Int = 65536): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val size = read(buffer)
            if (size < 0) return output.toByteArray()
            if (output.size() + size > limit) throw ConnectionFailure(ConnectionError.PROTOCOL)
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
                var paneInputEnabled: Boolean? = null
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
                        "paneInputEnabled" -> {
                            if (paneInputEnabled != null || reader.peek() != JsonToken.BOOLEAN) {
                                throw ConnectionFailure(ConnectionError.PROTOCOL)
                            }
                            paneInputEnabled = reader.nextBoolean()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (!versionSeen || revision.isNullOrEmpty() || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw ConnectionFailure(ConnectionError.PROTOCOL)
                }
                return Snapshot(revision, workspace, paneInputEnabled == true)
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
        var folder: WorkspaceFolder? = null
        readFields { field ->
            when (field) {
                "id" -> id = readString()
                "name" -> name = readString()
                "paneId" -> paneId = requireId(readString())
                "folder" -> folder = readFolder()
                else -> skipValue()
            }
        }
        return WorkspaceWindow(requireId(id), requireString(name), paneId, folder)
    }

    private fun JsonReader.readFolder(): WorkspaceFolder? {
        // Invalid optional metadata should not hide the window or change pane eligibility.
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            return null
        }
        var key: String? = null
        var name: String? = null
        val seen = mutableSetOf<String>()
        var valid = true
        beginObject()
        while (hasNext()) {
            val field = nextName()
            if (!seen.add(field)) valid = false
            when {
                field == "key" && peek() == JsonToken.STRING -> key = nextString()
                field == "name" && peek() == JsonToken.STRING -> name = nextString()
                else -> skipValue()
            }
        }
        endObject()
        if (!valid || key.isNullOrEmpty() || name.isNullOrBlank()) return null
        if (name != "/" && '/' in name) return null
        return WorkspaceFolder(key, name)
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
