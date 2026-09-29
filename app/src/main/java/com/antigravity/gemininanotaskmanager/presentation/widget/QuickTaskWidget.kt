package com.antigravity.gemininanotaskmanager.presentation.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.antigravity.gemininanotaskmanager.R
import com.antigravity.gemininanotaskmanager.TaskManagerApplication
import com.antigravity.gemininanotaskmanager.data.local.toDomain
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.repository.TaskChangeListener
import com.antigravity.gemininanotaskmanager.domain.time.DueDateFormatter
import com.antigravity.gemininanotaskmanager.presentation.assistant.AssistantActivity
import com.antigravity.gemininanotaskmanager.presentation.main.MainActivity
import com.antigravity.gemininanotaskmanager.presentation.theme.color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Widget de Lumi (Jetpack Glance): carita de Lumi, contadores, las 3 próximas tareas y botón de voz.
 * Se refresca solo cuando cambian las tareas (ver [WidgetUpdater]).
 */
class QuickTaskWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as TaskManagerApplication
        val active = app.database.taskDao().getActiveTasksSnapshot().map { it.toDomain() }
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val dueToday = active.count { t -> t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true }
        // Lo urgente primero; luego lo que vence antes
        val next = active.sortedWith(compareBy<Task>({ if (it.priority == com.antigravity.gemininanotaskmanager.domain.model.TaskPriority.HIGH) 0 else 1 }, { it.dueAt ?: Long.MAX_VALUE }, { -it.createdAt })).take(3)

        // PendingIntent de Activity: permitido por Android 14+ (un startActivity desde un
        // ActionCallback/broadcast sería un "background activity launch" y se bloquearía)
        val talk = AssistantActivity.intent(context, startListening = true, compact = true)
        val openApp = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        provideContent { WidgetContent(active.size, dueToday, next, talk, openApp) }
    }

    @Composable
    private fun WidgetContent(pending: Int, dueToday: Int, next: List<Task>, talk: Intent, openApp: Intent) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(14.dp)
        ) {
            // Cabecera: Lumi + contadores (toca para abrir la app)
            Row(
                modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openApp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(ImageProvider(R.drawable.lumi_logo), contentDescription = "Lumi", modifier = GlanceModifier.size(30.dp))
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text("Lumi", style = TextStyle(color = ink(), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Text(
                        if (pending == 0) "Todo al día ✨" else "$pending pendientes · $dueToday para hoy",
                        style = TextStyle(color = inkSecondary(), fontSize = 11.sp)
                    )
                }
            }

            Spacer(GlanceModifier.height(8.dp))

            // Próximas tareas
            Column(GlanceModifier.defaultWeight().fillMaxWidth()) {
                if (next.isEmpty()) {
                    Text("Nada pendiente. Di «Oye Lumi» o toca abajo.", style = TextStyle(color = inkSecondary(), fontSize = 12.sp))
                }
                next.forEach { task ->
                    TaskRow(task, openApp)
                    Spacer(GlanceModifier.height(4.dp))
                }
            }

            // Botón de voz
            Box(
                modifier = GlanceModifier.fillMaxWidth().height(38.dp)
                    .background(ImageProvider(R.drawable.widget_button_bg))
                    .clickable(actionStartActivity(talk)),
                contentAlignment = Alignment.Center
            ) {
                Text("Hablar con Lumi", style = TextStyle(color = ColorProvider(day = Color(0xFF0B0D12), night = Color(0xFF1A0B13)), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
        }
    }

    @Composable
    private fun TaskRow(task: Task, openApp: Intent) {
        val overdue = task.dueAt?.let { DueDateFormatter.isOverdue(it, task.dueHasTime) } == true
        Row(
            modifier = GlanceModifier.fillMaxWidth().background(ImageProvider(R.drawable.widget_row_bg))
                .padding(horizontal = 10.dp, vertical = 6.dp).clickable(actionStartActivity(openApp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Punto de color de la categoría (claro/oscuro) en vez de emoji
            Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(ColorProvider(day = task.category.color(false), night = task.category.color(true)))) {}
            Spacer(GlanceModifier.width(8.dp))
            Text(
                task.title, maxLines = 1,
                style = TextStyle(color = ink(), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight()
            )
            task.dueAt?.let {
                Text(
                    DueDateFormatter.format(it, task.dueHasTime),
                    maxLines = 1,
                    style = TextStyle(color = if (overdue) ColorProvider(day = Color(0xFFDC2626), night = Color(0xFFF87171)) else ColorProvider(day = Color(0xFF0284C7), night = Color(0xFFF5A9D0)), fontSize = 11.sp)
                )
            }
        }
    }

    // Colores día/noche: el widget sigue el tema del sistema
    private fun ink() = ColorProvider(day = Color(0xFF0B0D12), night = Color(0xFFF5F5F7))
    private fun inkSecondary() = ColorProvider(day = Color(0xFF5B6472), night = Color(0xFFA1A1AA))
}

/** AppWidgetReceiver que registra el Widget de Glance en el sistema Android */
class QuickTaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickTaskWidget()
}

/** Refresca el widget tras cualquier cambio de tareas. */
class WidgetUpdater(private val context: Context) : TaskChangeListener {
    override suspend fun onTaskSaved(taskId: Long) = refresh()
    override suspend fun onTaskDeleted(taskId: Long, googleTaskId: String?, calendarEventId: Long?) = refresh()
    private suspend fun refresh() { runCatching { QuickTaskWidget().updateAll(context) } }
}
