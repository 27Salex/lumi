package io.github.salex27.lumi.domain.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The PC-side services the phone can point at a server. */
enum class ServerService { HUB, SEARCH, PCVIEW, REMOTE }

/**
 * One server (a PC) the user defined: a name, a [scheme] + [host] and an optional token (the Lumi Hub phone token).
 * The port (and path) belong to each service, see [ServiceEndpoint]. [url] is the 1.1.4 full address, kept only so a
 * migration never loses data (it is not used any more once [host] is filled).
 */
@Serializable
data class ServerProfile(val id: String, val name: String, val url: String = "", val token: String = "", val scheme: String = "https", val host: String = "")

/** The port and optional path one service uses on its server. */
@Serializable
data class ServiceEndpoint(val port: Int, val path: String = "")

/** A URL split into its parts. */
data class ParsedUrl(val scheme: String, val host: String, val port: Int?, val path: String)

/** All servers plus which one each service uses (null = none; web search then runs on the phone). */
@Serializable
data class ServerState(
    val servers: List<ServerProfile> = emptyList(),
    val hub: String? = null,
    val search: String? = null,
    val pcview: String? = null,
    val hubEp: ServiceEndpoint? = null,
    val searchEp: ServiceEndpoint? = null,
    val pcEp: ServiceEndpoint? = null,
    /** Remote control (RDP): optional own server (null = the My PC server) and port (null = 3389). Added in 1.1.8, absent in older states. */
    val remote: String? = null,
    val remoteEp: ServiceEndpoint? = null,
    val ver: Int = 1
) {
    fun endpointFor(service: ServerService): ServiceEndpoint = when (service) {
        ServerService.HUB -> hubEp ?: ServiceEndpoint(ServerLogic.DEFAULT_HUB_PORT)
        ServerService.PCVIEW -> pcEp ?: hubEp ?: ServiceEndpoint(ServerLogic.DEFAULT_HUB_PORT) // My PC lives on the hub
        ServerService.SEARCH -> searchEp ?: ServiceEndpoint(ServerLogic.DEFAULT_SEARCH_PORT)
        ServerService.REMOTE -> remoteEp ?: ServiceEndpoint(ServerLogic.DEFAULT_RDP_PORT)
    }

    fun idFor(service: ServerService): String? = when (service) {
        ServerService.HUB -> hub
        ServerService.SEARCH -> search
        ServerService.PCVIEW -> pcview
        ServerService.REMOTE -> remote ?: pcview ?: hub // remote control follows My PC unless set
    }

    fun serverFor(service: ServerService): ServerProfile? = idFor(service)?.let { id -> servers.firstOrNull { it.id == id } }
}

/** Pure rules of the server profiles (tested): migration, assignment, validation. */
object ServerLogic {
    const val DEFAULT_ID = "default"
    const val SEARX_ID = "searxng"
    const val DEFAULT_HUB_PORT = 8443
    const val DEFAULT_SEARCH_PORT = 8444
    const val DEFAULT_RDP_PORT = 3389
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val PARSE = Regex("^(?:([a-z][a-z0-9+.-]*)://)?([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?(/[^ ?#]*)?/?$", RegexOption.IGNORE_CASE)

    /** Splits what the user pasted ("host", "host:8444", "https://host:8443/x/") into scheme, host, port and path; null if not http(s). */
    fun splitUrl(input: String): ParsedUrl? {
        val m = PARSE.matchEntire(input.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase().ifEmpty { "https" }
        if (scheme != "http" && scheme != "https") return null
        val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
        if (port != null && port !in 1..65535) return null
        val path = m.groupValues[4].trimEnd('/')
        return ParsedUrl(scheme, m.groupValues[2], port, path)
    }

    /** The full base URL of [service] (scheme://host:port/path), or null when no server is assigned / the host is invalid. */
    fun baseUrl(state: ServerState, service: ServerService): String? {
        val server = state.serverFor(service) ?: return null
        if (service == ServerService.REMOTE) return rdpTarget(state)?.let { "${it.first}:${it.second}" }
        val ep = state.endpointFor(service)
        return compose(server.scheme, server.host.ifBlank { splitUrl(server.url)?.host.orEmpty() }, ep.port, if (service == ServerService.SEARCH) ep.path else "")
    }

    /** Host (never the token) and port of the PC's remote desktop, or null when no server / invalid host. */
    fun rdpTarget(state: ServerState): Pair<String, Int>? {
        val server = state.serverFor(ServerService.REMOTE) ?: return null
        val host = server.host.ifBlank { splitUrl(server.url)?.host.orEmpty() }
        val port = state.endpointFor(ServerService.REMOTE).port
        if (host.isBlank() || !Regex("^[A-Za-z0-9.-]+$").matches(host) || port !in 1..65535) return null
        return host to port
    }

    fun compose(scheme: String, host: String, port: Int, path: String = ""): String? {
        if (host.isBlank() || !Regex("^[A-Za-z0-9.-]+$").matches(host) || port !in 1..65535) return null
        val p = path.trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
        return "${if (scheme == "http") "http" else "https"}://$host:$port$p"
    }

    /** 1.1.4 stored one full URL (usually with its port) per server: split it, give each assigned service that port. */
    fun upgrade(state: ServerState): ServerState {
        if (state.ver >= 2) return state
        val servers = state.servers.map { s ->
            val p = splitUrl(s.url)
            if (s.host.isBlank() && p != null) s.copy(scheme = p.scheme, host = p.host) else s
        }
        fun ep(id: String?): ServiceEndpoint? {
            val p = state.servers.firstOrNull { it.id == id }?.let { splitUrl(it.url) } ?: return null
            return ServiceEndpoint(p.port ?: if (p.scheme == "http") 80 else 443, p.path)
        }
        // endpoints already set are kept: a state built in the new format must not lose its ports
        val hubEp = state.hubEp ?: ep(state.hub)
        return state.copy(
            servers = servers, hubEp = hubEp,
            searchEp = state.searchEp ?: ep(state.search),
            pcEp = state.pcEp ?: ep(state.pcview)?.takeIf { it != hubEp },
            ver = 2
        )
    }

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
        return upgrade(ServerState(servers, hub = hub, search = search, pcview = hub))
    }

    fun assign(state: ServerState, service: ServerService, id: String?): ServerState {
        val valid = id?.takeIf { wanted -> state.servers.any { it.id == wanted } }
        return when (service) {
            ServerService.HUB -> state.copy(hub = valid)
            ServerService.SEARCH -> state.copy(search = valid)
            ServerService.PCVIEW -> state.copy(pcview = valid)
        ServerService.REMOTE -> state.copy(remote = valid)
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
        hub = state.hub.takeIf { it != id }, search = state.search.takeIf { it != id }, pcview = state.pcview.takeIf { it != id }, remote = state.remote.takeIf { it != id }
    )

    fun setEndpoint(state: ServerState, service: ServerService, ep: ServiceEndpoint?): ServerState = when (service) {
        ServerService.HUB -> state.copy(hubEp = ep)
        ServerService.SEARCH -> state.copy(searchEp = ep)
        ServerService.PCVIEW -> state.copy(pcEp = ep)
        ServerService.REMOTE -> state.copy(remoteEp = ep)
    }

    fun newId(state: ServerState): String {
        var n = state.servers.size + 1
        while (state.servers.any { it.id == "s$n" }) n++
        return "s$n"
    }

    fun encode(state: ServerState): String = json.encodeToString(ServerState.serializer(), state)
    fun decode(text: String): ServerState? = runCatching { upgrade(json.decodeFromString(ServerState.serializer(), text)) }.getOrNull()

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
enum class ServerTestError { NOT_CONFIGURED, BAD_URL, UNREACHABLE, TIMEOUT, TLS, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, SERVER_ERROR, BAD_ANSWER, PC_ROUTES_MISSING, PC_DISABLED, RDP_CLOSED, OTHER }

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
