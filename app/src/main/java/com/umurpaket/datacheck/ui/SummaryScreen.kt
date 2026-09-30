package com.umurpaket.datacheck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umurpaket.datacheck.AppViewModel
import com.umurpaket.datacheck.R
import com.umurpaket.datacheck.data.Level
import com.umurpaket.datacheck.fmt

private class Summary(
    val total: Int,
    val scanned: Int,
    val notFound: Int,
    val levels: Map<Level, Pair<Int, Int>>, // total, discan
    val byAge: List<Triple<String, Int, Int>>,
    val byAttempt: List<Triple<String, Int, Int>>,
    val oldestMissing: List<Triple<String, Int?, Level>>,
    /** Kolom teks berkategori (mis. "Status Longtail") -> jumlah per nilai. */
    val categories: List<Pair<String, List<Triple<String, Int, Int>>>>,
)

@Composable
fun SummaryScreen(vm: AppViewModel, onExport: () -> Unit) {
    val v = vm.version
    val s = vm.settings
    var confirmReset by remember { mutableStateOf(false) }
    val cols = vm.meta.columns
    val sum = remember(v, s, cols) {
        val a = vm.aging()
        val pk = vm.packages.toList()
        val ages = pk.map { a.age(it) }
        val levels = Level.entries.filter { it != Level.NOT_FOUND }.associateWith { l ->
            var t = 0; var sc = 0
            pk.forEachIndexed { i, p -> if (a.level(ages[i]) == l) { t++; if (p.scanCount > 0) sc++ } }
            t to sc
        }
        val maxBucket = 14
        val byAge = (0..maxBucket + 1).map { b ->
            var t = 0; var sc = 0
            pk.forEachIndexed { i, p ->
                val ag = ages[i] ?: return@forEachIndexed
                if ((b <= maxBucket && ag == b) || (b > maxBucket && ag > maxBucket)) { t++; if (p.scanCount > 0) sc++ }
            }
            Triple(if (b > maxBucket) "H+${maxBucket + 1}+" else "H+$b", t, sc)
        }.filter { it.second > 0 }
        val byAttempt = (0..7).map { b ->
            var t = 0; var sc = 0
            for (p in pk) {
                val at = p.attempt ?: continue
                if ((b < 7 && at == b) || (b == 7 && at >= 7)) { t++; if (p.scanCount > 0) sc++ }
            }
            Triple(if (b == 7) "7+" else "$b", t, sc)
        }.filter { it.second > 0 }
        val missing = pk.indices.filter { pk[it].scanCount == 0 }
            .sortedByDescending { ages[it] ?: -1 }.take(30)
            .map { Triple(pk[it].resi, ages[it], a.level(ages[it])) }
        // kolom teks dengan sedikit variasi nilai (2–12), selain resi/umur/attempt
        val categories = cols.others.filter { it !in cols.age && it != cols.attempt }.mapNotNull { c ->
            val groups = HashMap<String, IntArray>()
            for (p in pk) {
                val v = p.values.getOrNull(c)?.trim().orEmpty().ifEmpty { "(kosong)" }
                val g = groups.getOrPut(v) { IntArray(2) }
                g[0]++
                if (p.scanCount > 0) g[1]++
                if (groups.size > 12) return@mapNotNull null
            }
            if (groups.size < 2) return@mapNotNull null
            cols.name(c) to groups.entries.sortedByDescending { it.value[0] }.map { Triple(it.key, it.value[0], it.value[1]) }
        }.take(4)
        Summary(
            pk.size, pk.count { it.scanCount > 0 },
            vm.scans.filter { !it.found }.map { it.resi }.distinct().size,
            levels, byAge, byAttempt, missing, categories,
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = gapSmall) {
                Stat("Total paket", fmt(sum.total), Color(0xFF1F3A93), Modifier.weight(1f))
                Stat("Sudah discan", fmt(sum.scanned) + pct(sum.scanned, sum.total), Color(0xFF2E7D32), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = gapSmall) {
                Stat("Belum discan", fmt(sum.total - sum.scanned), Color(0xFFE65100), Modifier.weight(1f))
                Stat("Tidak ada di data", fmt(sum.notFound), Level.NOT_FOUND.color, Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle("Status umur")
                    val max = sum.levels.values.maxOfOrNull { it.first } ?: 0
                    val labels = mapOf(
                        Level.OK to "Aman", Level.WARN to "Perhatian", Level.CRIT to "Kritis", Level.UNKNOWN to "Umur ?",
                    )
                    sum.levels.forEach { (l, c) -> BarRow(labels[l] ?: l.label, c.first, max, l.color, c.second, l.color) }
                    Text(
                        "Aman < H+${s.warnDays} • Perhatian H+${s.warnDays}–H+${s.critDays - 1} • Kritis ≥ H+${s.critDays} • dasar: ${cols.age.firstOrNull()?.let { cols.name(it) } ?: "-"}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (sum.byAge.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle("Sebaran umur paket")
                    val a = vm.aging()
                    val max = sum.byAge.maxOf { it.second }
                    sum.byAge.forEach { (label, t, sc) ->
                        val age = label.removePrefix("H+").removeSuffix("+").toIntOrNull()
                        BarRow(label, t, max, a.level(age).color, sc, MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        if (sum.byAttempt.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle(cols.name(cols.attempt))
                    val max = sum.byAttempt.maxOf { it.second }
                    sum.byAttempt.forEach { (label, t, sc) ->
                        val high = (label.removeSuffix("+").toIntOrNull() ?: 0) >= s.attemptWarn
                        BarRow("$label kali", t, max, if (high) Color(0xFFC62828) else Color(0xFF1F3A93), sc, MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        for ((title, groups) in sum.categories) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle(title)
                    val max = groups.maxOf { it.second }
                    groups.forEach { (label, t, sc) -> BarRow(label, t, max, Color(0xFF3949AB), sc, MaterialTheme.colorScheme.outline) }
                }
            }
        }
        if (sum.oldestMissing.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle("Belum discan — umur tertua")
                    sum.oldestMissing.forEach { (resi, age, level) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Mono(resi, 14) }
                            AgeBadge(age, level)
                        }
                    }
                    if (sum.total - sum.scanned > sum.oldestMissing.size) {
                        Text(
                            "Daftar lengkap ada di file export (sheet \"Belum Discan\").",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = gapSmall) {
                Button(onClick = onExport, Modifier.fillMaxWidth(), enabled = vm.busy == null && sum.total > 0) {
                    androidx.compose.material3.Icon(painterResource(R.drawable.ic_download), null, Modifier.padding(end = 6.dp))
                    Text("Export laporan Excel")
                }
                OutlinedButton(onClick = { confirmReset = true }, Modifier.fillMaxWidth(), enabled = vm.scans.isNotEmpty()) {
                    Text("Mulai sesi scan baru")
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Mulai sesi scan baru?") },
            text = { Text("Riwayat scan (${fmt(vm.scans.size)}) dan status \"sudah discan\" dihapus. Data paket tetap ada. Export laporan dulu bila perlu.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetSession() }) { Text("Mulai baru") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Batal") } },
        )
    }
}

private fun pct(a: Int, b: Int) = if (b > 0) "  (${a * 100 / b}%)" else ""

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}
