package io.github.salex27.lumi.domain.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Semantic version (`1.2.3`, `v1.2.3-beta.1`, `1.2`): precedence follows semver.org, build metadata is ignored. */
data class Semver(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<Semver> {

    val isPrerelease get() = pre.isNotEmpty()

    override fun compareTo(other: Semver): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.compareTo(pre.size) // a release beats its prereleases
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers rank below alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val RE = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String?): Semver? {
            val m = RE.matchEntire(text.orEmpty().trim()) ?: return null
            val (maj, min, pat, pre) = m.destructured
            return Semver(
                maj.toIntOrNull() ?: return null, min.toIntOrNull() ?: 0, pat.toIntOrNull() ?: 0,
                if (pre.isEmpty()) emptyList() else pre.split('.')
            )
        }
    }
}

/** A release worth offering: its APK asset is already chosen. */
data class ReleaseInfo(
    val tag: String,
    val version: Semver,
    val notes: String,
    val pageUrl: String,
    val apkName: String?,
    val apkUrl: String?,
    val apkSize: Long,
    /** Lowercase hex sha256 from the asset `digest` ("sha256:..."), if GitHub provided one. */
    val sha256: String?
) {
    val hasApk get() = apkUrl != null
}

/** Parsing of `GET /repos/{owner}/{repo}/releases/latest` and the update policy (pure, unit-tested). */
object UpdateLogic {

    sealed interface Parsed {
        data class Release(val info: ReleaseInfo) : Parsed
        /** Draft or prerelease: never offered. */
        data object Ignored : Parsed
        data object Invalid : Parsed
    }

    fun parseRelease(json: String): Parsed {
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return Parsed.Invalid
        val tag = o.str("tag_name") ?: return Parsed.Invalid
        val version = Semver.parse(tag) ?: return Parsed.Invalid
        if (o.bool("draft") || o.bool("prerelease") || version.isPrerelease) return Parsed.Ignored
        val asset = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("name").orEmpty().endsWith(".apk", ignoreCase = true) && it.str("browser_download_url") != null }
        return Parsed.Release(
            ReleaseInfo(
                tag, version, o.str("body").orEmpty(), o.str("html_url").orEmpty(),
                asset?.str("name"), asset?.str("browser_download_url"),
                (asset?.get("size") as? JsonPrimitive)?.longOrNull ?: -1L,
                asset?.str("digest")?.let { sha256Of(it) }
            )
        )
    }

    /** "sha256:ABC..." gives "abc..."; other algorithms or malformed values give null. */
    fun sha256Of(digest: String): String? {
        if (!digest.trim().startsWith("sha256:", ignoreCase = true)) return null
        val hex = digest.trim().substringAfter(':').lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    fun isNewer(release: Semver, installed: String): Boolean {
        val current = Semver.parse(installed) ?: return false
        return release > current
    }

    fun shouldCheck(lastCheckAt: Long, now: Long, intervalMs: Long = CHECK_INTERVAL_MS) =
        lastCheckAt <= 0 || now - lastCheckAt >= intervalMs || now < lastCheckAt

    /** Release notes for a card: markdown noise removed, capped at [max] characters on a word boundary. */
    fun trimNotes(body: String, max: Int = 360): String {
        val clean = body.lines()
            .map { it.trim().trimStart('#', '*', '-', '>', ' ').replace("**", "").replace("`", "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (clean.length <= max) return clean
        return clean.take(max).substringBeforeLast(' ').ifBlank { clean.take(max) }.trimEnd('.', ',', ' ', '\n') + "…"
    }

    const val CHECK_INTERVAL_MS = 12 * 3_600_000L

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
