package io.github.salex27.lumi.service.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import io.github.salex27.lumi.domain.assistant.IncomingMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * «¿Qué me han escrito?»: Lumi lee las notificaciones de mensajes SIN LEER (WhatsApp, Telegram, SMS, correo…)
 * y puede responder con la acción «Responder» de la propia notificación, como Android Auto.
 * Nada se guarda en disco: solo se miran las notificaciones activas, y desaparecen de Lumi al leerlas en su app.
 * El usuario lo activa una vez en Ajustes → Acceso a notificaciones.
 */
class LumiNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications?.forEach { capture(it) } }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        messages.clear(); replies.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        messages.remove(sbn.key); replies.remove(sbn.key)
    }

    private fun capture(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val isMessage = style != null || n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL ||
            sbn.packageName in MESSAGING_APPS
        if (!isMessage) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        val extras = n.extras
        val reply = n.actions?.firstOrNull { a ->
            a.remoteInputs?.isNotEmpty() == true && (a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || a.remoteInputs.any { it.allowFreeFormInput })
        }
        val list = if (style != null && style.messages.isNotEmpty()) {
            val conv = style.conversationTitle?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            style.messages.mapNotNull { m ->
                val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = m.person?.name?.toString() ?: conv
                IncomingMessage(sbn.key, sbn.packageName, app, conv.ifBlank { sender }, sender, text, m.timestamp, style.isGroupConversation, reply != null)
            }
        } else {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() || text.isBlank()) emptyList()
            else listOf(IncomingMessage(sbn.key, sbn.packageName, app, title, title, text, sbn.postTime, false, reply != null))
        }
        if (list.isEmpty()) return
        messages[sbn.key] = list.takeLast(10)
        if (reply != null) replies[sbn.key] = reply else replies.remove(sbn.key)
    }

    companion object {
        @Volatile private var instance: LumiNotificationListener? = null
        private val messages = ConcurrentHashMap<String, List<IncomingMessage>>()
        private val replies = ConcurrentHashMap<String, Notification.Action>()

        private val MESSAGING_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.facebook.orca", "com.instagram.android",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.discord", "com.slack", "com.microsoft.teams"
        )

        /** ¿El usuario le dio a Lumi acceso a las notificaciones? */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, LumiNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Pantalla del sistema para concederlo (directa a Lumi en Android 11+). */
        fun settingsIntent(context: Context): Intent =
            Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", ComponentName(context, LumiNotificationListener::class.java).flattenToString())
                .takeIf { it.resolveActivity(context.packageManager) != null }
                ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        /** Mensajes sin leer ahora mismo (de las notificaciones activas), del más antiguo al más reciente. */
        fun unread(): List<IncomingMessage> = messages.values.flatten()
            .distinctBy { Triple(it.conversation, it.sender, it.text) }.sortedBy { it.time }

        /** Responde con la acción «Responder» de la notificación. false si ya no existe (se leyó o se borró). */
        fun reply(context: Context, key: String, text: String): Boolean {
            val action = replies[key] ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return runCatching { action.actionIntent.send(context, 0, intent) }.isSuccess.also { ok ->
                if (ok) { messages.remove(key); replies.remove(key) }
            }
        }
    }
}
