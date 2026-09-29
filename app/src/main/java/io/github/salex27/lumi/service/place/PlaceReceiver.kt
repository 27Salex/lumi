package io.github.salex27.lumi.service.place

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.domain.model.PlaceTrigger
import io.github.salex27.lumi.service.reminder.ReminderReceiver
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Arriving at / leaving a saved place → reminder of the linked tasks (same format as timed reminders). */
class PlaceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("PlaceReceiver", "Geofence error ${event.errorCode}")
            return
        }
        val app = context.applicationContext as TaskManagerApplication
        val pending = goAsync()
        scope.launch {
            try {
                event.triggeringGeofences.orEmpty().forEach { fence ->
                    val id = PlaceReminderManager.taskIdOf(fence.requestId) ?: return@forEach
                    val task = app.repository.getTask(id) ?: return@forEach
                    val trigger = task.placeTrigger ?: return@forEach
                    if (!task.isActive || !trigger.notify) return@forEach
                    ReminderReceiver.show(context, task, label(context, trigger))
                    app.placeReminders.markFired(task)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun label(context: Context, t: PlaceTrigger): String {
        val en = io.github.salex27.lumi.domain.assistant.ReplyLanguage.app == io.github.salex27.lumi.domain.assistant.Lang.EN
        val name = PlaceTrigger.displayName(t.place, io.github.salex27.lumi.domain.assistant.ReplyLanguage.app)
        return if (t.onArrive) context.getString(io.github.salex27.lumi.R.string.notif_arrived_at, if (en) (if (t.place == "casa") "home" else "at $name") else PlaceTrigger.withArticle("en", t.place))
        else context.getString(io.github.salex27.lumi.R.string.notif_leaving, if (en) name else PlaceTrigger.withArticle("de", t.place))
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
