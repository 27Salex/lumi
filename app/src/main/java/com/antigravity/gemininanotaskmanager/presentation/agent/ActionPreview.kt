package com.antigravity.gemininanotaskmanager.presentation.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.antigravity.gemininanotaskmanager.domain.assistant.DeviceCommand
import com.antigravity.gemininanotaskmanager.domain.assistant.TaskActions
import com.antigravity.gemininanotaskmanager.domain.model.Task

/**
 * A quién llamará / escribirá el botón de una tarea («Avisar a Roberto»), resuelto YA al crearla: así el usuario ve
 * que está vinculada a su contacto (antes solo se sabía al saltar el aviso, y parecía que no hacía nada).
 */
object ActionPreview {

    data class Preview(
        val action: TaskActions.Action,
        /** Nombre del contacto encontrado (o null). */
        val contactName: String?,
        val number: String?,
        /** Qué falta para que funcione («no encuentro a Roberto en tus contactos»…); null = todo bien. */
        val problem: String?
    ) {
        val command: DeviceCommand get() = action.command
    }

    fun resolve(context: Context, aliases: ContactAliases, task: Task): Preview? {
        val action = TaskActions.detect(task) ?: return null
        val spoken = when (val c = action.command) {
            is DeviceCommand.Call -> c.contact
            is DeviceCommand.Message -> c.contact
            else -> return Preview(action, null, null, null)
        }
        aliases.find(spoken)?.let { return Preview(action, it.name, it.number, null) }
        if (spoken.count(Char::isDigit) >= 6) return Preview(action, spoken, spoken, null)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return Preview(action, null, null, "Necesito acceso a tus contactos para encontrar a «$spoken» (te lo pediré al tocar el botón).")
        val found = runCatching { DeviceActions.findContacts(context, spoken) }.getOrDefault(emptyList())
        return when {
            found.isEmpty() -> Preview(action, null, null, "No encuentro a «$spoken» en tus contactos: revisa el nombre o añade un alias en Ajustes → Contactos rápidos.")
            found.size == 1 -> Preview(action, found.first().name, found.first().number, null)
            else -> Preview(action, null, null, "Hay ${found.size} contactos que encajan con «$spoken»; al tocar el botón te preguntaré cuál.")
        }
    }

    /** Frase para la respuesta al crear la tarea. */
    fun sentence(p: Preview, task: Task): String {
        val whenText = if (task.placeTrigger != null) "Entonces" else "Cuando te avise"
        val what = when (val c = p.command) {
            is DeviceCommand.Call -> "llamar a ${p.contactName ?: c.contact}"
            is DeviceCommand.Message -> "${if (c.whatsapp) "enviar un WhatsApp" else "enviar un SMS"} a ${p.contactName ?: c.contact}" +
                if (c.text.isNotBlank()) " («${c.text}»)" else ""
            else -> p.action.label.lowercase()
        }
        return " $whenText tendrás un botón para $what; solo tendrás que tocar enviar." .let { base ->
            if (p.command is DeviceCommand.Call) base.replace("; solo tendrás que tocar enviar.", ".") else base
        } + (p.problem?.let { " Ojo: $it" } ?: "")
    }
}
