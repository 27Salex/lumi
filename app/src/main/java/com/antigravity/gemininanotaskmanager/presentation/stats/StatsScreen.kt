package com.antigravity.gemininanotaskmanager.presentation.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.antigravity.gemininanotaskmanager.presentation.theme.Lumi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.gemininanotaskmanager.domain.stats.DayStat
import com.antigravity.gemininanotaskmanager.domain.stats.StatsRange
import com.antigravity.gemininanotaskmanager.domain.stats.StatsSummary
import com.antigravity.gemininanotaskmanager.presentation.components.LumiMark
import com.antigravity.gemininanotaskmanager.presentation.components.LumiState
import com.antigravity.gemininanotaskmanager.presentation.components.TypewriterText
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/**
 * Tinta de gráficos según el tema. Series: pasos validados de la paleta de referencia (skill dataviz)
 * — azul (slot 1) y naranja (slot 2), paso claro en modo claro y paso oscuro en modo oscuro.
 */
private data class ChartInk(
    val primary: Color, val secondary: Color, val muted: Color, val grid: Color, val baseline: Color,
    val surface: Color, val completed: Color, val created: Color, val good: Color, val tooltip: Color, val border: Color
)

@Composable
private fun chartInk(): ChartInk {
    val c = Lumi.colors
    return ChartInk(
        primary = c.textPrimary, secondary = c.textSecondary, muted = c.textTertiary,
        grid = c.outline, baseline = if (c.isDark) Color(0xFF383835) else Color(0xFFC3C2B7),
        surface = c.surface,
        completed = if (c.isDark) Color(0xFF3987E5) else Color(0xFF2A78D6),
        created = if (c.isDark) Color(0xFFD95926) else Color(0xFFEB6834),
        good = c.success, tooltip = c.elevated, border = c.outline
    )
}

private val ES = Locale.forLanguageTag("es-ES")

@Composable
fun StatsScreen(summary: StatsSummary?, onRangeChange: (StatsRange) -> Unit) {
    val k = chartInk()
    Column(
        Modifier.fillMaxSize().background(Lumi.colors.background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Progreso", style = MaterialTheme.typography.headlineMedium, color = k.primary, modifier = Modifier.weight(1f))
            RangeToggle(summary?.range ?: StatsRange.WEEK, onRangeChange)
        }
        if (summary == null) return@Column

        // Cifras clave (tiles, no gráficos: una sola cifra no necesita un gráfico)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                "Completadas", "${summary.completed}", Modifier.weight(1f),
                sub = when {
                    summary.delta > 0 -> "▲ ${summary.delta} vs anterior" to k.good
                    summary.delta < 0 -> "▼ ${-summary.delta} vs anterior" to k.secondary
                    else -> "= que el anterior" to k.muted
                }
            )
            StatTile("Ratio", summary.completionRate?.let { "$it %" } ?: "—", Modifier.weight(1f), sub = "de lo creado" to k.muted)
            StatTile("Racha", "${summary.streak}", Modifier.weight(1f), sub = (if (summary.streak == 1) "día" else "días") to k.muted)
        }

        // Comentario de Lumi
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(k.surface)
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            LumiMark(LumiState.IDLE, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            TypewriterText(
                StatsInsight.text(summary), style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = k.primary,
                animate = false
            )
        }

        ChartCard("Tareas completadas por día") {
            BarChart(summary.days, summary.range)
        }

        ChartCard("Creadas vs completadas") {
            Legend(listOf("Completadas" to k.completed, "Creadas" to k.created))
            Spacer(Modifier.height(8.dp))
            LineChart(summary.days, summary.range)
        }

        if (summary.byCategory.isNotEmpty()) {
            ChartCard("Completadas por categoría") {
                val max = summary.byCategory.maxOf { it.second }.coerceAtLeast(1)
                summary.byCategory.forEach { (cat, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${cat.emoji} ${cat.label}", color = k.secondary, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                        Box(Modifier.weight(1f).height(14.dp)) {
                            Box(
                                Modifier.fillMaxWidth(n / max.toFloat()).height(14.dp)
                                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(k.completed)
                            )
                        }
                        Text("$n", color = k.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp).padding(start = 8.dp))
                    }
                }
            }
        }

        DataTable(summary.days)
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun RangeToggle(range: StatsRange, onChange: (StatsRange) -> Unit) {
    val k = chartInk()
    Row(Modifier.clip(RoundedCornerShape(50)).background(Lumi.colors.muted).padding(3.dp)) {
        StatsRange.entries.forEach { r ->
            val sel = r == range
            Text(
                r.label, style = MaterialTheme.typography.labelLarge, color = if (sel) Lumi.colors.textPrimary else k.secondary,
                modifier = Modifier.clip(RoundedCornerShape(50)).then(if (sel) Modifier.background(Lumi.colors.elevated) else Modifier)
                    .clickable { onChange(r) }.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, sub: Pair<String, Color>) {
    val k = chartInk()
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(k.surface)
            .padding(12.dp)
    ) {
        Text(label, color = k.secondary, fontSize = 12.sp)
        Text(value, color = k.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(sub.first, color = sub.second, fontSize = 11.sp)
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val k = chartInk()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(k.surface)
            .padding(16.dp)
    ) {
        Text(title, color = k.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    val k = chartInk()
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, color = k.secondary, fontSize = 12.sp) // el texto usa tinta, no el color de la serie
            }
        }
    }
}

private fun dayLabel(d: DayStat, range: StatsRange, index: Int, count: Int): String? = when (range) {
    StatsRange.WEEK -> d.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, ES).take(2).replaceFirstChar { it.uppercase() }
    StatsRange.MONTH -> if (index % 5 == 0 || index == count - 1) "${d.date.dayOfMonth}" else null
}

private fun tooltipDate(d: DayStat) = d.date.format(DateTimeFormatter.ofPattern("EEE d MMM", ES))

/** Barras de una sola serie: sin leyenda (el título la nombra), extremo redondeado de 4dp anclado a la base. */
@Composable
private fun BarChart(days: List<DayStat>, range: StatsRange) {
    val k = chartInk()
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val max = (days.maxOfOrNull { it.completed } ?: 0).coerceAtLeast(1)
    val ticks = niceTicks(max)
    val top = ticks.last()

    Box(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(170.dp)
                .semantics { contentDescription = "Gráfico de barras de tareas completadas por día" }
                .pointerInput(days) {
                    detectTapGestures { pos ->
                        val left = 28.dp.toPx()
                        val slot = (size.width - left) / days.size
                        val i = ((pos.x - left) / slot).toInt()
                        selected = if (i in days.indices && selected != i) i else null
                    }
                }
        ) {
            val left = 28.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val plotH = bottom - 6.dp.toPx()
            val slot = (size.width - left) / days.size
            val gap = 2.dp.toPx().coerceAtLeast(slot * 0.25f)

            ticks.forEach { t ->
                val y = bottom - plotH * t / top
                drawLine(if (t == 0) k.baseline else k.grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                drawText(t.toString(), 0f, y + 4.dp.toPx(), k.muted, 10.sp.toPx())
            }
            days.forEachIndexed { i, d ->
                val x = left + slot * i + gap / 2
                val w = slot - gap
                if (d.completed > 0) {
                    val h = plotH * d.completed / top
                    val alpha = if (selected == null || selected == i) 1f else 0.45f
                    val r = 4.dp.toPx().coerceAtMost(w / 2)
                    drawPath(
                        Path().apply {
                            addRoundRect(RoundRect(x, bottom - h, x + w, bottom, CornerRadius(r), CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero))
                        },
                        k.completed.copy(alpha = alpha)
                    )
                }
                dayLabel(d, range, i, days.size)?.let { drawText(it, x + w / 2, size.height - 2.dp.toPx(), k.muted, 10.sp.toPx(), center = true) }
            }
        }
        selected?.let { i -> Tooltip(tooltipDate(days[i]), listOf("${days[i].completed} completadas" to k.completed)) }
    }
}

/** Dos series (misma unidad, un solo eje): líneas de 2dp, leyenda + etiqueta directa al final. */
@Composable
private fun LineChart(days: List<DayStat>, range: StatsRange) {
    val k = chartInk()
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val max = (days.maxOfOrNull { maxOf(it.completed, it.created) } ?: 0).coerceAtLeast(1)
    val ticks = niceTicks(max)
    val top = ticks.last()

    Box(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(170.dp)
                .semantics { contentDescription = "Gráfico de líneas de tareas creadas y completadas por día" }
                .pointerInput(days) {
                    detectTapGestures { pos ->
                        val left = 28.dp.toPx()
                        val right = 26.dp.toPx()
                        val step = (size.width - left - right) / (days.size - 1).coerceAtLeast(1)
                        val i = ((pos.x - left) / step + 0.5f).toInt().coerceIn(0, days.size - 1)
                        selected = if (selected == i) null else i
                    }
                }
        ) {
            val left = 28.dp.toPx()
            val right = 26.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val plotH = bottom - 6.dp.toPx()
            val step = (size.width - left - right) / (days.size - 1).coerceAtLeast(1)
            fun pt(i: Int, v: Int) = Offset(left + step * i, bottom - plotH * v / top)

            ticks.forEach { t ->
                val y = bottom - plotH * t / top
                drawLine(if (t == 0) k.baseline else k.grid, Offset(left, y), Offset(size.width - right, y), strokeWidth = 1f)
                drawText(t.toString(), 0f, y + 4.dp.toPx(), k.muted, 10.sp.toPx())
            }
            selected?.let { i -> drawLine(k.muted, Offset(left + step * i, 6.dp.toPx()), Offset(left + step * i, bottom), strokeWidth = 1f) }

            listOf(k.created to { d: DayStat -> d.created }, k.completed to { d: DayStat -> d.completed }).forEach { (color, value) ->
                val path = Path()
                days.forEachIndexed { i, d -> val p = pt(i, value(d)); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                // Etiqueta directa al final de la línea (valor del último día)
                val last = pt(days.lastIndex, value(days.last()))
                drawCircle(color, 3.dp.toPx(), last)
                selected?.let { i ->
                    val p = pt(i, value(days[i]))
                    drawCircle(k.surface, 6.dp.toPx(), p) // anillo del color de la superficie
                    drawCircle(color, 4.dp.toPx(), p)
                }
            }
            days.forEachIndexed { i, d ->
                dayLabel(d, range, i, days.size)?.let { drawText(it, left + step * i, size.height - 2.dp.toPx(), k.muted, 10.sp.toPx(), center = true) }
            }
        }
        selected?.let { i ->
            Tooltip(tooltipDate(days[i]), listOf("${days[i].completed} completadas" to k.completed, "${days[i].created} creadas" to k.created))
        }
    }
}

@Composable
private fun Tooltip(title: String, rows: List<Pair<String, Color>>) {
    val k = chartInk()
    Column(
        Modifier.padding(start = 34.dp).clip(RoundedCornerShape(10.dp)).background(k.tooltip)
            .border(0.5.dp, k.border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(title.replaceFirstChar { it.uppercase() }, color = k.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        rows.forEach { (text, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(text, color = k.secondary, fontSize = 12.sp)
            }
        }
    }
}

/** Vista de tabla (accesibilidad): los mismos datos, sin depender del color. */
@Composable
private fun DataTable(days: List<DayStat>) {
    val k = chartInk()
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (open) "Ocultar datos ▲" else "Ver datos ▼", color = k.secondary, fontSize = 13.sp,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp)
        )
        if (open) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Día", color = k.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Creadas", color = k.muted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                Text("Completadas", color = k.muted, fontSize = 12.sp, modifier = Modifier.width(90.dp))
            }
            days.reversed().forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(tooltipDate(d), color = k.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${d.created}", color = k.primary, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                    Text("${d.completed}", color = k.primary, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                }
            }
        }
    }
}

/** Ticks "bonitos" (0, paso, 2·paso...) que cubren el máximo. */
private fun niceTicks(max: Int): List<Int> {
    val step = when {
        max <= 4 -> 1
        max <= 10 -> 2
        max <= 25 -> 5
        else -> 10
    }
    val top = ((max + step - 1) / step) * step
    return (0..top step step).toList()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawText(
    text: String, x: Float, y: Float, color: Color, sizePx: Float, center: Boolean = false
) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        textSize = sizePx
        textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

