package io.github.salex27.lumi.presentation.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.domain.assistant.TaskActions
import io.github.salex27.lumi.domain.model.Task

/**
 * Who a task's button ("Let Roberto know") will call / text, resolved RIGHT when it is created: that way the user sees
 * it is linked to their contact (before, it was only known when the reminder fired, and it looked like it did nothing).
 */
object ActionPreview {

    data class Preview(
        val action: TaskActions.Action,
        /** Name of the contact found (or null). */
        val contactName: String?,
        val number: String?,
        /** What is missing for it to work ("I can't find Roberto in your contacts"…); null = all good. */
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
        if (!granted) return Preview(action, null, null, io.github.salex27.lumi.domain.assistant.ReplyLanguage.t("Necesito acceso a tus contactos para encontrar a «$spoken» (te lo pediré al tocar el botón).", "I need access to your contacts to find «$spoken» (I'll ask when you tap the button)."))
        val found = runCatching { DeviceActions.findContacts(context, spoken) }.getOrDefault(emptyList())
        return when {
            found.isEmpty() -> Preview(action, null, null, io.github.salex27.lumi.domain.assistant.ReplyLanguage.t("No encuentro a «$spoken» en tus contactos: revisa el nombre o añade un alias en Ajustes → Contactos rápidos.", "I can't find «$spoken» in your contacts: check the name or add an alias in Settings → Quick contacts."))
            found.size == 1 -> Preview(action, found.first().name, found.first().number, null)
            else -> Preview(action, null, null, io.github.salex27.lumi.domain.assistant.ReplyLanguage.t("Hay ${found.size} contactos que encajan con «$spoken»; al tocar el botón te preguntaré cuál.", "${found.size} contacts match «$spoken»; when you tap the button I'll ask which one."))
        }
    }

    /** Sentence for the reply when the task is created. */
    fun sentence(p: Preview, task: Task): String {
        val t = io.github.salex27.lumi.domain.assistant.ReplyLanguage::t
        val name = p.contactName ?: when (val c = p.command) {
            is DeviceCommand.Call -> c.contact
            is DeviceCommand.Message -> c.contact
            else -> ""
        }
        val what = when (val c = p.command) {
            is DeviceCommand.Call -> t("llamar a $name", "call $name")
            is DeviceCommand.Message -> (if (c.whatsapp) t("enviar un WhatsApp a $name", "send a WhatsApp to $name") else t("enviar un SMS a $name", "send a text to $name")) +
                if (c.text.isNotBlank()) " («${c.text}»)" else ""
            else -> p.action.label.lowercase()
        }
        val whenText = if (task.placeTrigger != null) t("Entonces", "Then") else t("Cuando te avise", "When I remind you")
        val base = if (p.command is DeviceCommand.Call) t(" $whenText tendrás un botón para $what.", " $whenText you'll have a button to $what.")
        else t(" $whenText tendrás un botón para $what; solo tendrás que tocar enviar.", " $whenText you'll have a button to $what; you'll just have to tap send.")
        return base + (p.problem?.let { t(" Ojo: $it", " Note: $it") } ?: "")
    }
}
