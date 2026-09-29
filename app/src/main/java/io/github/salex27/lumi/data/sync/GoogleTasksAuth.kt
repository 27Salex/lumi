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
 * OAuth para Google Tasks con la Authorization API de Google Identity Services.
 * No hace falta client ID en el código: Google identifica la app por nombre de paquete + SHA-1
 * del certificado, registrados en un cliente OAuth "Android" (docs/GOOGLE_TASKS_SETUP.md).
 * Tras el primer consentimiento, los tokens se obtienen en silencio.
 */
class GoogleTasksAuth(private val context: Context) {

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        /** Hace falta que el usuario dé su consentimiento: lanzar este PendingIntent desde una Activity. */
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

    /** Procesa la respuesta de la pantalla de consentimiento. */
    fun resultFromIntent(data: Intent?): Result = try {
        toResult(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    } catch (e: Exception) {
        Result.Failed(explain(e))
    }

    private fun toResult(r: AuthorizationResult): Result = when {
        r.hasResolution() -> r.pendingIntent?.let { Result.NeedsConsent(it) } ?: Result.Failed("Falta consentimiento")
        r.accessToken != null -> Result.Token(r.accessToken!!)
        else -> Result.Failed("Google no devolvió un token")
    }

    private fun explain(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            // Código 10 = DEVELOPER_ERROR: falta el cliente OAuth Android o el SHA-1 no coincide
            msg.startsWith("10:") || msg.contains("DEVELOPER_ERROR") ->
                "Falta configurar el cliente OAuth en Google Cloud (paquete + SHA-1). Mira la guía en Ajustes."
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
