package com.umurpaket.datacheck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umurpaket.datacheck.AppViewModel
import com.umurpaket.datacheck.data.Level

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SectionTitle("Batas umur paket")
                Stepper("Perhatian (kuning) mulai", "H+${s.warnDays}", Level.WARN) { d ->
                    val w = (s.warnDays + d).coerceIn(1, 60)
                    vm.updateSettings(s.copy(warnDays = w, critDays = maxOf(s.critDays, w + 1)))
                }
                Stepper("Kritis (merah) mulai", "H+${s.critDays}", Level.CRIT) { d ->
                    vm.updateSettings(s.copy(critDays = (s.critDays + d).coerceIn(s.warnDays + 1, 90)))
                }
                Text(
                    "Hijau = H+0 s/d H+${s.warnDays - 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                val c = vm.meta.columns
                SectionTitle("Kolom file saat ini")
                Text("Semua kolom: ${c.names.joinToString(", ")}", fontSize = 13.sp)
                Text("No. Resi: ${c.name(c.resi)}", fontSize = 13.sp)
                Text("Dasar umur: ${c.age.joinToString(" → ") { c.name(it) }.ifBlank { "(tidak ada)" }}", fontSize = 13.sp)
                Text("Attempt: ${if (c.attempt >= 0) c.name(c.attempt) else "(tidak ada)"}", fontSize = 13.sp)
                Text(
                    "Nama kolom bebas, dipilih saat import. Kolom umur berisi angka (mis. 5) = umur hari saat file diimport, " +
                        "lalu bertambah otomatis tiap hari; berisi tanggal = umur dihitung sejak tanggal itu.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SectionTitle("Scan")
                Stepper("Tandai attempt tinggi mulai", "${s.attemptWarn}x", null) { d ->
                    vm.updateSettings(s.copy(attemptWarn = (s.attemptWarn + d).coerceIn(1, 20)))
                }
                Stepper("Abaikan barcode yang sama selama", "${s.cooldownSec} dtk", null) { d ->
                    vm.updateSettings(s.copy(cooldownSec = (s.cooldownSec + d).coerceIn(1, 30)))
                }
                Toggle("Bunyi", s.sound) { vm.updateSettings(s.copy(sound = it)) }
                Toggle("Getar", s.vibrate) { vm.updateSettings(s.copy(vibrate = it)) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Tes bunyi")
                Row(horizontalArrangement = gapSmall) {
                    FilledTonalButton(onClick = { vm.testSound(Level.OK) }, Modifier.weight(1f)) { Text("Aman") }
                    FilledTonalButton(onClick = { vm.testSound(Level.WARN) }, Modifier.weight(1f)) { Text("Perhatian") }
                    FilledTonalButton(onClick = { vm.testSound(Level.CRIT) }, Modifier.weight(1f)) { Text("Kritis") }
                }
                Row(horizontalArrangement = gapSmall) {
                    OutlinedButton(onClick = { vm.testSound(Level.NOT_FOUND) }, Modifier.weight(1f)) { Text("Tidak ada") }
                    OutlinedButton(onClick = { vm.testSound(Level.OK, dup = true) }, Modifier.weight(1f)) { Text("Scan ulang") }
                }
            }
        }
        Text(
            "Data Check 2 • versi 2.2.4",
            Modifier.fillMaxWidth().padding(8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun Stepper(label: String, value: String, level: Level?, onDelta: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (level != null) { Dot(level.color); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)) }
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        OutlinedButton(onClick = { onDelta(-1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("−", fontSize = 18.sp) }
        Text(value, Modifier.width(64.dp), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onDelta(1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("+", fontSize = 18.sp) }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
