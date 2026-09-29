package io.github.salex27.lumi.data.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth for Google Tasks with the Authorization API of Google Identity Services.
 * No client ID in the code: Google identifies the app by package name + certificate SHA-1, registered in an
 * "Android" OAuth client (docs/GOOGLE_TASKS_SETUP.md). After the first consent, tokens are obtained silently.
 */
class GoogleTasksAuth(private val context: Context) {

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        /** The user has to consent: launch this PendingIntent from an Activity. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Result
        data class Failed(val message: String) : Result
    }

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(TASKS_SCOPE)))
        .build()

    suspend fun authorize(): Result = try {
        toResult(Identity.getAuthorizationClient(context).authorize(request).await())
    } catch (e: Exception) {
        Result.Failed(explain(e))
    }

    /** Handles the consent screen result. */
    fun resultFromIntent(data: Intent?): Result = try {
        toResult(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    } catch (e: Exception) {
        Result.Failed(explain(e))
    }

    private fun toResult(r: AuthorizationResult): Result = when {
        r.hasResolution() -> r.pendingIntent?.let { Result.NeedsConsent(it) } ?: Result.Failed(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Falta consentimiento", "Consent missing"))
        r.accessToken != null -> Result.Token(r.accessToken!!)
        else -> Result.Failed(io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Google no devolvió un token", "Google returned no token"))
    }

    private fun explain(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            // Code 10 = DEVELOPER_ERROR: the Android OAuth client is missing or the SHA-1 does not match
            msg.startsWith("10:") || msg.contains("DEVELOPER_ERROR") ->
                io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui("Falta configurar el cliente OAuth en Google Cloud (paquete + SHA-1). Mira la guía en Ajustes.", "The OAuth client isn't set up in Google Cloud (package + SHA-1). See the guide in Settings.")
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    companion object {
        const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"
    }
}
