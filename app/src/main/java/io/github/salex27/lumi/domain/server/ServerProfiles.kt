package io.github.salex27.lumi.domain.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The PC-side services the phone can point at a server. */
enum class ServerService { HUB, SEARCH, PCVIEW }

/** One server the user defined: a name, a base URL and an optional token (the Lumi Hub phone token, or none). */
@Serializable
data class ServerProfile(val id: String, val name: String, val url: String, val token: String = "")

/** All servers plus which one each service uses (null = none; web search then runs on the phone). */
@Serializable
data class ServerState(
    val servers: List<ServerProfile> = emptyList(),
    val hub: String? = null,
    val search: String? = null,
    val pcview: String? = null
) {
    fun idFor(service: ServerService): String? = when (service) {
        ServerService.HUB -> hub
        ServerService.SEARCH -> search
        ServerService.PCVIEW -> pcview
    }

    fun serverFor(service: ServerService): ServerProfile? = idFor(service)?.let { id -> servers.firstOrNull { it.id == id } }
}

/** Pure rules of the server profiles (tested): migration, assignment, validation. */
object ServerLogic {
    const val DEFAULT_ID = "default"
    const val SEARX_ID = "searxng"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * First run of the servers feature: the old Lumi Hub address + token become the default server (used by Hub and
     * My PC), and an old SearXNG address the user had chosen becomes its own server used for web search. The old
     * preferences are never touched.
     */
    fun migrate(hubAddress: String, hubToken: String, searxUrl: String, searxWasChosen: Boolean, defaultName: String, searxName: String): ServerState {
        val servers = mutableListOf<ServerProfile>()
        var hub: String? = null
        var search: String? = null
        if (hubAddress.isNotBlank() || hubToken.isNotBlank()) {
            servers += ServerProfile(DEFAULT_ID, defaultName, hubAddress.trim(), hubToken.trim())
            hub = DEFAULT_ID
        }
        if (searxUrl.isNotBlank() && searxWasChosen) {
            servers += ServerProfile(SEARX_ID, searxName, searxUrl.trim())
            search = SEARX_ID
        }
        return ServerState(servers, hub = hub, search = search, pcview = hub)
    }

    fun assign(state: ServerState, service: ServerService, id: String?): ServerState {
        val valid = id?.takeIf { wanted -> state.servers.any { it.id == wanted } }
        return when (service) {
            ServerService.HUB -> state.copy(hub = valid)
            ServerService.SEARCH -> state.copy(search = valid)
            ServerService.PCVIEW -> state.copy(pcview = valid)
        }
    }

    /** One tap: every service uses [id]. */
    fun useForAll(state: ServerState, id: String): ServerState =
        if (state.servers.none { it.id == id }) state else state.copy(hub = id, search = id, pcview = id)

    /** Adds or replaces a server; the first server added also becomes the Hub and My PC server. */
    fun upsert(state: ServerState, server: ServerProfile): ServerState {
        val exists = state.servers.any { it.id == server.id }
        val servers = if (exists) state.servers.map { if (it.id == server.id) server else it } else state.servers + server
        val first = state.servers.isEmpty()
        return state.copy(servers = servers, hub = state.hub ?: if (first) server.id else null, pcview = state.pcview ?: if (first) server.id else null)
    }

    /** Removing a server unassigns every service that used it (web search then falls back to the phone). */
    fun remove(state: ServerState, id: String): ServerState = state.copy(
        servers = state.servers.filterNot { it.id == id },
        hub = state.hub.takeIf { it != id }, search = state.search.takeIf { it != id }, pcview = state.pcview.takeIf { it != id }
    )

    fun newId(state: ServerState): String {
        var n = state.servers.size + 1
        while (state.servers.any { it.id == "s$n" }) n++
        return "s$n"
    }

    fun encode(state: ServerState): String = json.encodeToString(ServerState.serializer(), state)
    fun decode(text: String): ServerState? = runCatching { json.decodeFromString(ServerState.serializer(), text) }.getOrNull()

    private val HTTP = Regex("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?(/[^\\s?#]*)?$", RegexOption.IGNORE_CASE)

    /** A web search server may sit on the LAN over plain http; null when the text is not an http(s) base URL. */
    fun normalizeSearchUrl(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        return s.takeIf { HTTP.matches(it) }
    }
}

/** Why a connection test failed; every value has a localized message in the UI. */
enum class ServerTestError { NOT_CONFIGURED, BAD_URL, UNREACHABLE, TIMEOUT, TLS, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, SERVER_ERROR, BAD_ANSWER, OTHER }

object ServerTestErrors {
    /** [httpCode] when the server answered, [error] when it did not. */
    fun classify(httpCode: Int?, error: Throwable?): ServerTestError = when {
        httpCode != null -> when {
            httpCode == 401 -> ServerTestError.UNAUTHORIZED
            httpCode == 403 -> ServerTestError.FORBIDDEN
            httpCode == 404 -> ServerTestError.NOT_FOUND
            httpCode in 500..599 -> ServerTestError.SERVER_ERROR
            else -> ServerTestError.OTHER
        }
        error is java.net.SocketTimeoutException -> ServerTestError.TIMEOUT
        error is javax.net.ssl.SSLException -> ServerTestError.TLS
        error is java.net.UnknownHostException || error is java.net.ConnectException ||
            error is java.net.NoRouteToHostException || error is java.net.SocketException -> ServerTestError.UNREACHABLE
        error is kotlinx.serialization.SerializationException || error is IllegalArgumentException -> ServerTestError.BAD_ANSWER
        else -> ServerTestError.OTHER
    }
}
