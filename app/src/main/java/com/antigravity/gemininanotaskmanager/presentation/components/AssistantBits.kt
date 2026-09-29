package com.antigravity.gemininanotaskmanager.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import com.antigravity.gemininanotaskmanager.domain.time.DueDateFormatter
import com.antigravity.gemininanotaskmanager.presentation.theme.Lumi
import com.antigravity.gemininanotaskmanager.presentation.theme.color
import com.antigravity.gemininanotaskmanager.presentation.theme.icon

// ── Piezas básicas ─────────────────────────────────────────────────────────

/** Cabecera de sección en mayúsculas pequeñas (estilo iOS/Revolut). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Lumi.colors.textTertiary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Tarjeta plana con borde fino. */
@Composable
fun LumiCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Lumi.colors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content
    )
}

enum class PillStyle { PRIMARY, SECONDARY, GHOST }

@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, style: PillStyle = PillStyle.PRIMARY, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lumi.colors
    val (bg, fg) = when (style) {
        PillStyle.PRIMARY -> c.accent to c.onAccent
        PillStyle.SECONDARY -> c.muted to c.textPrimary
        PillStyle.GHOST -> Color.Transparent to c.accentText
    }
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else c.textTertiary,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (enabled) bg else c.muted)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/** Etiqueta de categoría: icono + nombre en el color de la categoría (nunca solo color). */
@Composable
fun CategoryLabel(category: TaskCategory, modifier: Modifier = Modifier) {
    val color = category.color(Lumi.colors.isDark)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(category.icon(), null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(category.label, style = MaterialTheme.typography.labelMedium, color = Lumi.colors.textSecondary)
    }
}

/** Píldora discreta con el motor de IA que respondió. */
@Composable
fun EngineBadge(engine: String, modifier: Modifier = Modifier) {
    if (engine.isBlank()) return
    Text(
        engine, style = MaterialTheme.typography.labelMedium, color = Lumi.colors.textTertiary, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Lumi.colors.muted).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Mini-fila de tarea dentro de una respuesta de Lumi. */
@Composable
fun TaskMiniChip(task: Task, modifier: Modifier = Modifier) {
    val c = Lumi.colors
    val done = task.status == TaskStatus.COMPLETED
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.muted).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (done) Icons.Default.Check else task.category.icon(), null, tint = if (done) c.success else task.category.color(c.isDark), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(task.title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        task.dueAt?.let { Text(DueDateFormatter.format(it, task.dueHasTime), style = MaterialTheme.typography.labelMedium, color = c.textSecondary) }
    }
}

// ── Fila de tarea (lista principal) ────────────────────────────────────────

/**
 * Fila plana: círculo para completar, título y una línea de metadatos (hora · categoría · repetición · reunión).
 * Desliza → derecha = hecha, izquierda = para mañana.
 */
@Composable
fun TaskRow(
    task: Task,
    onToggleDone: () -> Unit,
    onTomorrow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Lumi.colors
    val done = task.status == TaskStatus.COMPLETED
    val overdue = task.isActive && task.dueAt?.let { DueDateFormatter.isOverdue(it, task.dueHasTime) } == true
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (it) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleDone()
                SwipeToDismissBoxValue.EndToStart -> if (task.isActive) onTomorrow()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // la fila vuelve a su sitio; la lista se actualiza sola
        }
    )
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        backgroundContent = {
            val toRight = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (toRight) c.success.copy(alpha = 0.15f) else c.accentContainer).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End
            ) {
                Text(if (toRight) (if (done) "Pendiente" else "Hecha") else "Mañana", style = MaterialTheme.typography.labelLarge,
                    color = if (toRight) c.success else c.accentText)
            }
        }
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.background).clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggleDone),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .then(if (done) Modifier.background(c.accent) else Modifier.border(1.6.dp, if (overdue) c.danger else c.textTertiary, CircleShape)),
                    contentAlignment = Alignment.Center
                ) { if (done) Icon(Icons.Default.Check, "Hecha", tint = c.onAccent, modifier = Modifier.size(14.dp)) }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title, style = MaterialTheme.typography.bodyLarge, color = if (done) c.textTertiary else c.textPrimary,
                    textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (task.priority != TaskPriority.NONE && task.isActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PriorityFlag(task.priority, Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(task.priority.label, style = MaterialTheme.typography.labelMedium, color = priorityTint(task.priority), maxLines = 1)
                        }
                    }
                    task.dueAt?.let {
                        MetaItem(Icons.Outlined.Schedule, DueDateFormatter.format(it, task.dueHasTime), if (overdue) c.danger else c.textSecondary)
                    }
                    CategoryLabel(task.category)
                    task.recurrence?.let { MetaItem(Icons.Outlined.Repeat, null, c.textSecondary) }
                    task.meeting?.let { MetaItem(Icons.Outlined.Event, it.title, c.textSecondary) }
                    task.placeTrigger?.let { MetaItem(Icons.Outlined.Place, it.place.replaceFirstChar { ch -> ch.uppercase() }, c.textSecondary) }
                }
            }
        }
    }
}

/** Bandera de prioridad: rellena en Alta/Media, contorno en Baja. Siempre va con texto al lado (no solo color). */
@Composable
fun PriorityFlag(priority: TaskPriority, modifier: Modifier = Modifier) {
    Icon(
        if (priority == TaskPriority.LOW) Icons.Outlined.Flag else Icons.Filled.Flag,
        contentDescription = "Prioridad ${priority.label.lowercase()}",
        tint = priorityTint(priority),
        modifier = modifier
    )
}

@Composable
fun priorityTint(priority: TaskPriority): Color {
    val c = Lumi.colors
    return when (priority) {
        TaskPriority.HIGH -> c.accentText
        TaskPriority.MEDIUM -> c.textSecondary
        else -> c.textTertiary
    }
}

@Composable
private fun MetaItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String?, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        text?.let {
            Spacer(Modifier.width(3.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Barra inteligente (dentro de la app) ───────────────────────────────────

/**
 * Barra de Lumi dentro de la app: logo (toca → hablar) + campo de texto (escribe una tarea o pregunta).
 * Al enviar, abre la conversación con ese texto.
 */
@Composable
fun SmartBar(onSubmit: (String) -> Unit, onVoice: () -> Unit, modifier: Modifier = Modifier, placeholder: String = "Pregunta o añade una tarea…") {
    val c = Lumi.colors
    var text by remember { mutableStateOf("") }
    Row(
        modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(28.dp)).background(c.elevated).border(1.dp, c.outline, RoundedCornerShape(28.dp))
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onVoice), contentAlignment = Alignment.Center) {
            LumiMark(LumiState.IDLE, size = 34.dp)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accentText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSubmit(text.trim()); text = "" } }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        AnimatedVisibility(text.isNotBlank(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(
                Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).background(c.accent)
                    .clickable { onSubmit(text.trim()); text = "" },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Enviar", tint = c.onAccent, modifier = Modifier.size(20.dp)) }
        }
    }
}

// ── Ajustes estilo iOS ─────────────────────────────────────────────────────

/** Grupo de filas con esquinas redondeadas y separadores finos. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Lumi.colors.surface), content = content)
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Lumi.colors.textPrimary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lumi.colors.textSecondary) }
        }
        trailing?.invoke(this)
    }
}

@Composable
fun ListDivider() = Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Lumi.colors.outline))

/** Texto de estado con punto de color + etiqueta (el color nunca va solo). */
@Composable
fun StatusText(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Lumi.colors.textSecondary, fontWeight = FontWeight.Medium)
    }
}
