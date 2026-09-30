package com.umurpaket.datacheck.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umurpaket.datacheck.AppViewModel
import com.umurpaket.datacheck.R
import com.umurpaket.datacheck.data.Aging
import com.umurpaket.datacheck.data.Columns
import com.umurpaket.datacheck.data.Level
import com.umurpaket.datacheck.data.Pkg
import com.umurpaket.datacheck.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Filter(val label: String) {
    ALL("Semua"), OK("Aman"), WARN("Perhatian"), CRIT("Kritis"), UNKNOWN("Umur ?"), NOT_SCANNED("Belum discan"), SCANNED("Sudah discan")
}

private data class PRow(val p: Pkg, val age: Int?, val level: Level, val text: String)

@Composable
fun DataScreen(vm: AppViewModel, onImport: () -> Unit, onExport: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var confirmDelete by remember { mutableStateOf(false) }
    val v = vm.version
    val settings = vm.settings

    val rows = remember(v, settings) {
        val a: Aging = vm.aging()
        vm.packages.map { val age = a.age(it); PRow(it, age, a.level(age), it.values.joinToString(" ").uppercase()) }
            .sortedWith(compareByDescending<PRow> { it.age ?: -1 }.thenBy { it.p.resi })
    }
    val counts = remember(rows) {
        Filter.entries.associateWith { f -> rows.count { match(it, f) } }
    }
    val shown = remember(rows, query, filter) {
        val q = query.trim().uppercase()
        rows.filter { match(it, filter) && (q.isEmpty() || it.p.resi.contains(q) || it.text.contains(q)) }
    }

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(14.dp)) {
                val m = vm.meta
                Text(if (m.fileName.isBlank()) "Belum ada data" else m.fileName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (m.importedAt > 0) {
                    Text(
                        "Diimport ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(m.importedAt))} • ${fmt(vm.packageCount)} paket",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text("Import file Excel (.xlsx) atau CSV. Nama & jumlah kolom bebas, minimal ada kolom No. Resi / No Waybill.", fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = gapSmall) {
                    Button(onClick = onImport, Modifier.weight(1f), enabled = vm.busy == null) {
                        Icon(painterResource(R.drawable.ic_upload), null, Modifier.padding(end = 6.dp))
                        Text("Import Excel")
                    }
                    OutlinedButton(onClick = onExport, Modifier.weight(1f), enabled = vm.busy == null && vm.packageCount > 0) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.padding(end = 6.dp))
                        Text("Export")
                    }
                }
                if (vm.packageCount > 0) {
                    TextButton(onClick = { confirmDelete = true }, Modifier.align(Alignment.End)) {
                        Text("Hapus semua data", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
            placeholder = { Text("Cari resi atau isi kolom (mis. status)") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Hapus") } },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = gapSmall,
        ) {
            Filter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f, onClick = { filter = f },
                    label = { Text("${f.label} (${fmt(counts[f] ?: 0)})") },
                    leadingIcon = if (levelOf(f) != null) { { Dot(levelOf(f)!!.color) } } else null,
                )
            }
        }
        Text(
            "Menampilkan ${fmt(shown.size)} paket • urut umur tertua",
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
        )
        HorizontalDivider()
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (vm.packageCount == 0) "Data kosong — import file Excel dulu" else "Tidak ada yang cocok", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.p.resi }) { r -> PkgRow(r, vm.meta.columns, settings.attemptWarn) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus semua data?") },
            text = { Text("${fmt(vm.packageCount)} paket dan seluruh riwayat scan akan dihapus dari aplikasi. File Excel asli tidak terpengaruh.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.clearAll() }) { Text("Hapus", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } },
        )
    }
}

private fun levelOf(f: Filter): Level? = when (f) {
    Filter.OK -> Level.OK
    Filter.WARN -> Level.WARN
    Filter.CRIT -> Level.CRIT
    Filter.UNKNOWN -> Level.UNKNOWN
    else -> null
}

private fun match(r: PRow, f: Filter): Boolean = when (f) {
    Filter.ALL -> true
    Filter.NOT_SCANNED -> r.p.scanCount == 0
    Filter.SCANNED -> r.p.scanCount > 0
    else -> r.level == levelOf(f)
}

@Composable
private fun PkgRow(r: PRow, cols: Columns, attemptWarn: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(r.p.resi, 15)
                if (r.p.scanCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.CheckCircle, "Sudah discan", tint = Color(0xFF2E7D32), modifier = Modifier.padding(top = 1.dp))
                }
            }
            val detail = cols.others.mapNotNull { i ->
                r.p.values.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { "${cols.name(i)}: $it" }
            }.joinToString("  •  ")
            if (detail.isNotEmpty()) {
                Text(
                    detail, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if ((r.p.attempt ?: 0) >= attemptWarn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }
        }
        AgeBadge(r.age, r.level)
    }
    HorizontalDivider(color = Color(0xFFEDEEF3))
}

/** Pilihan satu kolom dari daftar judul kolom file. */
@Composable
private fun ColumnPicker(label: String, names: List<String>, selected: Int, allowNone: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
                Text(if (selected in names.indices) names[selected] else "(tidak ada)", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (allowNone) DropdownMenuItem(text = { Text("(tidak ada)") }, onClick = { open = false; onSelect(-1) })
                names.forEachIndexed { i, n ->
                    DropdownMenuItem(text = { Text(n) }, onClick = { open = false; onSelect(i) })
                }
            }
        }
    }
}

/**
 * Dialog setelah file dibaca: tampilkan kolom yang terdeteksi (nama bebas sesuai file),
 * pengguna bisa mengganti kolom No. Resi, dasar umur, dan attempt sebelum import.
 */
@Composable
fun ImportDialog(vm: AppViewModel) {
    val pi = vm.pendingImport ?: return
    val g = pi.guess.columns
    var resi by remember(pi) { mutableStateOf(g.resi) }
    var age by remember(pi) { mutableStateOf(g.age.firstOrNull() ?: -1) }
    var attempt by remember(pi) { mutableStateOf(g.attempt) }
    var merge by remember(pi) { mutableStateOf(false) }
    val hasData = vm.packageCount > 0

    AlertDialog(
        onDismissRequest = vm::cancelImport,
        title = { Text("Import ${pi.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${fmt(pi.dataRows)} baris • ${g.names.size} kolom terbaca:", fontSize = 13.sp)
                Text(g.names.joinToString("  |  "), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                ColumnPicker("Kolom No. Resi", g.names, resi, false) { resi = it }
                ColumnPicker("Kolom dasar umur (angka umur atau tanggal)", g.names, age, true) { age = it }
                ColumnPicker("Kolom delivery attempt (untuk tanda ⚠)", g.names, attempt, true) { attempt = it }
                Card {
                    Column(Modifier.padding(10.dp)) {
                        Text("Contoh baris pertama", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        g.names.forEachIndexed { i, n ->
                            Text("$n: ${pi.sample.getOrNull(i).orEmpty().ifBlank { "-" }}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (hasData) {
                    Text("Masih ada ${fmt(vm.packageCount)} paket & ${fmt(vm.scans.size)} riwayat scan:", fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !merge, onClick = { merge = false })
                        Text("Ganti semua (data & sesi scan lama dihapus)", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = merge, onClick = { merge = true })
                        Text("Gabungkan (status scan tetap)", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // kolom umur pilihan dipakai dulu, kolom tanggal lain yang terdeteksi jadi cadangan
                val ages = if (age < 0) emptyList() else (listOf(age) + g.age).distinct().filter { it != resi && it != attempt }
                vm.confirmImport(pi, Columns(g.names, resi, ages, if (attempt == resi) -1 else attempt), merge)
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Batal") } },
    )
}
