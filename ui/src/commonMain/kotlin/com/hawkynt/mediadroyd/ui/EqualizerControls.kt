package com.hawkynt.mediadroyd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroyd.player.Equalizer
import com.hawkynt.mediadroyd.player.EqualizerPreset
import com.hawkynt.mediadroyd.state.EqualizerSetting
import kotlin.math.round

/**
 * The equalizer (#451), as Settings and both gears show it: VLC's presets as chips, and under "Adjust the bands" the
 * preamp and the ten bands, each −20 … +20 dB in half-decibel steps. A band moved makes the setting the household's
 * own ("Custom").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerControls(setting: EqualizerSetting, onChange: (EqualizerSetting) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in EqualizerPreset.entries) {
                FilterChip(
                    selected = setting.preset == preset,
                    onClick = { onChange(EqualizerSetting.of(preset)) },
                    label = { Text(preset.label) },
                )
            }
            if (setting.preset == null) {
                FilterChip(selected = true, onClick = { open = true }, label = { Text("Custom") })
            }
        }
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide the bands" else "Adjust the bands") }
        if (open) {
            fun step(v: Float) = round(v * 2) / 2
            BandRow("Preamp", setting.preampDb) { onChange(setting.copy(preampDb = step(it))) }
            val bands = List(Equalizer.HZ.size) { setting.bandsDb.getOrNull(it) ?: 0f }
            Equalizer.HZ.forEachIndexed { i, hz ->
                val label = if (hz >= 1000) "${(hz / 1000).toInt()} kHz" else "${hz.toInt()} Hz"
                BandRow(label, bands[i]) { v ->
                    onChange(setting.copy(bandsDb = bands.toMutableList().also { it[i] = step(v) }))
                }
            }
        }
    }
}

@Composable
private fun BandRow(label: String, db: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 15.sp, modifier = Modifier.width(72.dp))
        Slider(
            value = db,
            onValueChange = onChange,
            valueRange = Equalizer.MIN_DB..Equalizer.MAX_DB,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%+.1f dB".format(db),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
    }
}
