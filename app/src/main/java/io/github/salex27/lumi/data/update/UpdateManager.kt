package io.github.salex27.lumi.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.salex27.lumi.BuildConfig
import io.github.salex27.lumi.domain.update.ReleaseInfo
import io.github.salex27.lumi.domain.update.UpdateLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Why an update step failed; the UI maps each to a readable message. */
enum class UpdateError { OFFLINE, RATE_LIMIT, SERVER, INVALID, NO_APK, DOWNLOAD, SIZE, CHECKSUM, SIGNATURE }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long) : UpdateState
    data class Ready(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val error: UpdateError, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * In-app update from GitHub releases (#11). No background service: the check runs when the app starts (at most every
 * 12 h, short timeout, off the main thread) and on demand from Settings. The release APKs are debug-signed, so an
 * update only installs over an app signed with the same key; [prepareInstall] checks that before the system
 * installer opens, to give a readable error instead of "package conflicts".
 *
 * Debug builds can point at a local mock server: the SharedPreferences file `updates` keys `debug_api_base`
 * (e.g. http://10.0.2.2:8765) and `debug_repo` are honoured only when [BuildConfig.DEBUG].
 */
class UpdateManager(private val context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val installed: String get() = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _lastCheck = MutableStateFlow(prefs.getLong(K_LAST, 0L))
    val lastCheckAt: StateFlow<Long> = _lastCheck.asStateFlow()

    private val _skipped = MutableStateFlow(prefs.getString(K_SKIPPED, null))
    /** Tag the user chose to skip; the Home card stays hidden for it. */
    val skippedTag: StateFlow<String?> = _skipped.asStateFlow()

    val currentVersion: String get() = installed

    private var job: Job? = null

    init {
        // A release found earlier is offered again until it is installed or a newer one replaces it
        prefs.getString(K_JSON, null)?.let { json ->
            (UpdateLogic.parseRelease(json) as? UpdateLogic.Parsed.Release)?.info
                ?.takeIf { UpdateLogic.isNewer(it.version, installed) }
                ?.let { _state.value = readyOrAvailable(it) }
        }
        // Old downloads (and abandoned partial files) are cleaned after a week
        runCatching { updatesDir().listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() } }
    }

    private fun readyOrAvailable(r: ReleaseInfo): UpdateState {
        val f = apkFile(r)
        return if (r.hasApk && f.isFile && verify(r, f) == null) UpdateState.Ready(r, f) else UpdateState.Available(r)
    }

    private val apiBase: String get() = (if (BuildConfig.DEBUG) prefs.getString("debug_api_base", null) else null) ?: DEFAULT_API
    private val repo: String get() = (if (BuildConfig.DEBUG) prefs.getString("debug_repo", null) else null) ?: DEFAULT_REPO

    // ── Check ────────────────────────────────────────────────────────────────────────────────────────────────

    /** App start: only if the last check is older than 12 h. Failures are silent (nobody asked). */
    fun checkOnStart() {
        if (!UpdateLogic.shouldCheck(_lastCheck.value, System.currentTimeMillis())) return
        check(manual = false)
    }

    /** [manual] = the user tapped "Check now": errors are shown. */
    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        val before = _state.value
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val outcome = withContext(Dispatchers.IO) { fetchLatest() }
            when (outcome) {
                is Fetch.Ok -> {
                    stamp()
                    when (val parsed = UpdateLogic.parseRelease(outcome.json)) {
                        is UpdateLogic.Parsed.Release -> {
                            val r = parsed.info
                            if (UpdateLogic.isNewer(r.version, installed)) {
                                prefs.edit().putString(K_JSON, outcome.json).apply()
                                _state.value = readyOrAvailable(r)
                            } else {
                                prefs.edit().remove(K_JSON).apply()
                                _state.value = UpdateState.UpToDate
                            }
                        }
                        UpdateLogic.Parsed.Ignored -> { prefs.edit().remove(K_JSON).apply(); _state.value = UpdateState.UpToDate }
                        UpdateLogic.Parsed.Invalid -> fail(UpdateError.INVALID, manual, before)
                    }
                }
                Fetch.NoReleases -> { stamp(); prefs.edit().remove(K_JSON).apply(); _state.value = UpdateState.UpToDate }
                is Fetch.Error -> fail(outcome.error, manual, before)
            }
        }
    }

    private fun fail(error: UpdateError, manual: Boolean, before: UpdateState) {
        _state.value = if (manual) UpdateState.Failed(error) else if (before is UpdateState.Checking) UpdateState.Idle else before
    }

    private fun stamp() {
        val now = System.currentTimeMillis()
        _lastCheck.value = now
        prefs.edit().putLong(K_LAST, now).apply()
    }

    private sealed interface Fetch {
        data class Ok(val json: String) : Fetch
        data object NoReleases : Fetch
        data class Error(val error: UpdateError) : Fetch
    }

    private fun fetchLatest(): Fetch {
        val conn = runCatching { URL("$apiBase/repos/$repo/releases/latest").openConnection() as HttpURLConnection }.getOrNull()
            ?: return Fetch.Error(UpdateError.INVALID)
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Lumi/$installed")
            when (val code = conn.responseCode) {
                200 -> Fetch.Ok(conn.inputStream.bufferedReader().use { it.readText() })
                404 -> Fetch.NoReleases
                403, 429 -> Fetch.Error(if (code == 429 || conn.getHeaderField("X-RateLimit-Remaining") == "0" || conn.getHeaderField("Retry-After") != null) UpdateError.RATE_LIMIT else UpdateError.SERVER)
                else -> Fetch.Error(UpdateError.SERVER)
            }
        } catch (e: IOException) {
            Fetch.Error(UpdateError.OFFLINE)
        } catch (e: Exception) {
            Fetch.Error(UpdateError.SERVER)
        } finally {
            conn.disconnect()
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────────────────────────────────────

    fun skip(release: ReleaseInfo) {
        _skipped.value = release.tag
        prefs.edit().putString(K_SKIPPED, release.tag).apply()
    }

    fun download(release: ReleaseInfo) {
        if (job?.isActive == true) return
        val url = release.apkUrl ?: run { _state.value = UpdateState.Failed(UpdateError.NO_APK, release); return }
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, 0, release.apkSize)
            val err = withContext(Dispatchers.IO) { downloadTo(release, url) }
            _state.value = if (err == null) UpdateState.Ready(release, apkFile(release)) else UpdateState.Failed(err, release)
        }
    }

    fun cancelDownload() {
        val s = _state.value as? UpdateState.Downloading ?: return
        job?.cancel()
        _state.value = UpdateState.Available(s.release)
    }

    fun dismissError() {
        val s = _state.value as? UpdateState.Failed ?: return
        _state.value = s.release?.let { readyOrAvailable(it) } ?: UpdateState.Idle
    }

    /**
     * Downloads with automatic resume: a dropped connection (common on mobile data) keeps the partial file and the
     * next attempt continues with a Range request; after [MAX_ATTEMPTS] the partial file stays for "Try again".
     */
    private suspend fun downloadTo(release: ReleaseInfo, url: String): UpdateError? {
        val target = apkFile(release)
        val part = File(target.parentFile, target.name + ".part")
        part.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                val have = if (part.isFile) part.length() else 0L
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", "Lumi/$installed")
                conn.instanceFollowRedirects = true
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                val resumed = code == 206 && have > 0
                android.util.Log.w("LumiUpdate", "GET have=$have code=$code range=${conn.getHeaderField("Content-Range")} len=${conn.contentLengthLong}")
                if (code == 416 && have > 0) { part.delete(); continue } // stale partial file: start over
                if (code !in 200..299) return UpdateError.DOWNLOAD
                var done = if (resumed) have else 0L
                val total = (conn.contentLengthLong.takeIf { it > 0 }?.plus(if (resumed) have else 0L)) ?: release.apkSize
                var lastEmit = 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (!coroutineContext.isActive) return null
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 150) { lastEmit = now; _state.update { UpdateState.Downloading(release, done, total) } }
                        }
                    }
                }
                val bad = verify(release, part)
                if (bad != null) { part.delete(); return bad }
                target.delete()
                return if (part.renameTo(target)) null else UpdateError.DOWNLOAD
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                android.util.Log.w("LumiUpdate", "download interrupted (attempt ${attempt + 1})", e)
                if (++attempt >= MAX_ATTEMPTS) return UpdateError.DOWNLOAD
                kotlinx.coroutines.delay(1_000)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Size and sha256 against the release data; null = fine. */
    private fun verify(release: ReleaseInfo, file: File): UpdateError? {
        if (release.apkSize > 0 && file.length() != release.apkSize) { android.util.Log.w("LumiUpdate", "size ${file.length()} != ${release.apkSize}"); return UpdateError.SIZE }
        val expected = release.sha256 ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) android.util.Log.w("LumiUpdate", "sha256 $actual != $expected")
        return if (actual == expected) null else UpdateError.CHECKSUM
    }

    // ── Install ──────────────────────────────────────────────────────────────────────────────────────────────

    sealed interface Install {
        /** The user must allow "Install unknown apps" for Lumi first. */
        data class NeedsPermission(val settings: Intent) : Install
        data class Go(val installer: Intent) : Install
        data class Blocked(val error: UpdateError) : Install
    }

    /**
     * Checks the downloaded APK is Lumi signed with the same key as the installed app (otherwise Android refuses it with
     * an obscure error), then builds the system installer intent.
     */
    fun prepareInstall(release: ReleaseInfo, file: File): Install {
        if (!sameSigner(file)) return Install.Blocked(UpdateError.SIGNATURE)
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            return Install.NeedsPermission(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Install.Go(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun reportSignature(release: ReleaseInfo) { _state.value = UpdateState.Failed(UpdateError.SIGNATURE, release) }

    @Suppress("DEPRECATION")
    private fun sameSigner(file: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        mine.isNotEmpty() && mine == theirs
    }.getOrDefault(false)

    private fun updatesDir() = File(context.cacheDir, "updates")
    private fun apkFile(r: ReleaseInfo) = File(updatesDir(), "lumi-${r.tag.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}.apk")

    companion object {
        const val DEFAULT_API = "https://api.github.com"
        const val DEFAULT_REPO = "27Salex/lumi"
        private const val TIMEOUT_MS = 8_000
        private const val MAX_ATTEMPTS = 6
        private const val K_LAST = "last_check"
        private const val K_SKIPPED = "skipped_tag"
        private const val K_JSON = "latest_json"
    }
}
