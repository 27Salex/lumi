package io.github.salex27.lumi.presentation.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.salex27.lumi.R
import io.github.salex27.lumi.TaskManagerApplication
import io.github.salex27.lumi.domain.assistant.BedtimeParser
import io.github.salex27.lumi.presentation.components.ListDivider
import io.github.salex27.lumi.presentation.components.ListGroup
import io.github.salex27.lumi.presentation.components.ListRow
import io.github.salex27.lumi.presentation.components.SectionHeader
import io.github.salex27.lumi.presentation.theme.Lumi
import io.github.salex27.lumi.service.checkin.AlarmSetStore
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Daily bedtime reminder: on/off, time and days of the week (Settings > Notifications and reminders). */
@Composable
fun BedtimeSettings(app: TaskManagerApplication) {
    val c = Lumi.colors
    val s by app.settings.settings.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.set_bedtime), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.set_bedtime_sub),
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    ListGroup {
        ListRow(stringResource(R.string.set_bedtime_toggle), stringResource(R.string.set_bedtime_toggle_sub),
            trailing = { Toggle(s.bedtimeEnabled) { on -> app.settings.update { it.copy(bedtimeEnabled = on) }; app.bedtime.schedule() } })
        if (s.bedtimeEnabled) {
            ListDivider()
            fun shift(delta: Int) {
                app.settings.update { it.copy(bedtimeMinutes = (it.bedtimeMinutes + delta + 24 * 60) % (24 * 60)) }; app.bedtime.schedule()
            }
            Stepper(stringResource(R.string.set_bedtime_time), "%d:%02d".format(s.bedtimeMinutes / 60, s.bedtimeMinutes % 60), { shift(-15) }, { shift(15) })
            ListDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..6) {
                    val on = BedtimeParser.includes(s.bedtimeDays, i)
                    Segment(DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3), on, Modifier.weight(1f)) {
                        // Never leave zero days selected
                        val mask = (s.bedtimeDays xor (1 shl i)).takeIf { it and BedtimeParser.ALL_DAYS != 0 } ?: s.bedtimeDays
                        app.settings.update { it.copy(bedtimeDays = mask) }; app.bedtime.schedule()
                    }
                }
            }
        }
    }
}

/** The wake-up alarms Lumi created through the clock app, with delete (Settings > Notifications and reminders). */
@Composable
fun WakeAlarmsSettings(app: TaskManagerApplication) {
    val c = Lumi.colors
    val context: Context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val entries = remember(tick) { app.alarmSets.live() }
    fun delete(minutes: Int) {
        AlarmSetStore.delete(context, minutes)
        app.alarmSets.remove(minutes); tick++
    }
    SectionHeader(stringResource(R.string.set_wake_alarms), Modifier.padding(start = 4.dp, top = 12.dp))
    Text(stringResource(R.string.set_wake_alarms_sub),
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    ListGroup {
        if (entries.isEmpty()) {
            Text(stringResource(R.string.set_wake_alarms_none), style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, modifier = Modifier.padding(16.dp))
        } else {
            entries.forEachIndexed { i, e ->
                if (i > 0) ListDivider()
                ListRow("%02d:%02d".format(e.minutes / 60, e.minutes % 60), null,
                    trailing = { Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge, color = c.accentText, modifier = Modifier.clickable { delete(e.minutes) }) })
            }
        }
    }
}
