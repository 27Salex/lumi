package io.github.salex27.lumi.data.server

import io.github.salex27.lumi.data.hub.HubSafety
import io.github.salex27.lumi.data.search.SearchParsers
import io.github.salex27.lumi.domain.server.ServerLogic
import io.github.salex27.lumi.domain.server.ServerState
import io.github.salex27.lumi.domain.server.ServerService
import io.github.salex27.lumi.domain.server.ServerTestError
import io.github.salex27.lumi.domain.server.ServerTestErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

sealed interface ServerTestResult {
    /** [detail] is plain data from the server (host name, agents, result count), shown as-is. */
    data class Ok(val detail: String) : ServerTestResult
    data class Failed(val error: ServerTestError, val code: Int? = null) : ServerTestResult
}

/** One connection test per server and service: the cheapest harmless GET the service needs. Never sends content. */
object ServerTester {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(service: ServerService, state: ServerState): ServerTestResult = withContext(Dispatchers.IO) {
        val server = state.serverFor(service)
        if (server == null) return@withContext ServerTestResult.Failed(ServerTestError.NOT_CONFIGURED)
        val full = ServerLogic.baseUrl(state, service) ?: return@withContext ServerTestResult.Failed(ServerTestError.BAD_URL)
        val base = when (service) {
            ServerService.SEARCH -> ServerLogic.normalizeSearchUrl(full)
            else -> HubSafety.normalizeAddress(full)
        } ?: return@withContext ServerTestResult.Failed(ServerTestError.BAD_URL)
        if (service != ServerService.SEARCH && server.token.isBlank()) return@withContext ServerTestResult.Failed(ServerTestError.NOT_CONFIGURED)
        val path = when (service) {
            ServerService.HUB -> "/health"
            ServerService.PCVIEW -> "/pc/status"
            ServerService.SEARCH -> "/search?q=lumi&format=json&categories=general"
        }
        try {
            val (code, body) = get(base + path, server.token)
            if (code == 404 && service == ServerService.PCVIEW) {
                // a hub that is up but older than the My PC routes answers /health and 404s /pc/*
                val hubUp = runCatching { get(base + "/health", server.token).first in 200..299 }.getOrDefault(false)
                return@withContext ServerTestResult.Failed(if (hubUp) ServerTestError.PC_ROUTES_MISSING else ServerTestError.NOT_FOUND, code)
            }
            if (code !in 200..299) return@withContext ServerTestResult.Failed(ServerTestErrors.classify(code, null), code)
            if (service == ServerService.PCVIEW && json.parseToJsonElement(body).jsonObject["state"]?.jsonPrimitive?.content == "disabled") {
                return@withContext ServerTestResult.Failed(ServerTestError.PC_DISABLED, code)
            }
            ServerTestResult.Ok(describe(service, body))
        } catch (e: Exception) {
            ServerTestResult.Failed(ServerTestErrors.classify(null, e))
        }
    }

    private fun describe(service: ServerService, body: String): String = when (service) {
        ServerService.HUB -> {
            val o = json.parseToJsonElement(body).jsonObject
            val host = o["host"]?.jsonPrimitive?.content.orEmpty()
            val agents = runCatching { o["agents"]!!.jsonArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } }.getOrDefault(emptyList())
            HubSafety.plain(listOf(host, agents.joinToString()).filter { it.isNotBlank() }.joinToString(" · ")).take(120)
        }
        ServerService.PCVIEW -> HubSafety.plain(json.parseToJsonElement(body).jsonObject["state"]?.jsonPrimitive?.content.orEmpty()).take(40)
        ServerService.SEARCH -> SearchParsers.searx(body).size.toString() // throws on non-JSON: reported as a bad answer
    }

    private fun get(url: String, token: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Lumi/1.0 (https://github.com/27Salex/lumi)")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText().take(200_000) } else ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

}
