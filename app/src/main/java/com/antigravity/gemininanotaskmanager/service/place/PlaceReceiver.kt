package com.antigravity.gemininanotaskmanager.service.place

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antigravity.gemininanotaskmanager.TaskManagerApplication
import com.antigravity.gemininanotaskmanager.domain.model.PlaceTrigger
import com.antigravity.gemininanotaskmanager.service.reminder.ReminderReceiver
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Llegada / salida de un lugar guardado → aviso de las tareas asociadas (mismo formato que los avisos por hora). */
class PlaceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("PlaceReceiver", "Error de geovalla ${event.errorCode}")
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
                    ReminderReceiver.show(context, task, label(trigger))
                    app.placeReminders.markFired(task)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun label(t: PlaceTrigger) =
        if (t.onArrive) "Ya estás ${PlaceTrigger.withArticle("en", t.place)}" else "Saliendo ${PlaceTrigger.withArticle("de", t.place)}"

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
