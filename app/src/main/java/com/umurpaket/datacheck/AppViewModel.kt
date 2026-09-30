package com.umurpaket.datacheck

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.umurpaket.datacheck.data.Aging
import com.umurpaket.datacheck.data.Code
import com.umurpaket.datacheck.data.ColumnGuess
import com.umurpaket.datacheck.data.Columns
import com.umurpaket.datacheck.data.CsvReader
import com.umurpaket.datacheck.data.Db
import com.umurpaket.datacheck.data.Importer
import com.umurpaket.datacheck.data.Level
import com.umurpaket.datacheck.data.Meta
import com.umurpaket.datacheck.data.Pkg
import com.umurpaket.datacheck.data.Prefs
import com.umurpaket.datacheck.data.ScanRecord
import com.umurpaket.datacheck.data.ScanResult
import com.umurpaket.datacheck.data.Settings
import com.umurpaket.datacheck.data.Source
import com.umurpaket.datacheck.data.XlsxReader
import com.umurpaket.datacheck.data.XlsxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** File yang sudah dibaca, menunggu konfirmasi pemetaan kolom dari pengguna. */
class PendingImport(val name: String, val rows: List<List<String>>, val guess: ColumnGuess) {
    val dataRows get() = rows.size - if (guess.hasHeader) 1 else 0
    val sample: List<String> get() = rows.getOrNull(if (guess.hasHeader) 1 else 0).orEmpty()
}

data class ScanStats(val total: Int, val scanned: Int, val critScanned: Int, val notFound: Int)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val db = Db(app)
    /** Semua penulisan DB berurutan agar batal-scan tidak mendahului simpan-scan. */
    private val dbWriter = Dispatchers.IO.limitedParallelism(1)
    private val prefs = Prefs(app)
    private val feedback = Feedback(app)

    var settings by mutableStateOf(prefs.loadSettings()); private set
    var meta by mutableStateOf(prefs.loadMeta()); private set
    private var pkgMap = HashMap<String, Pkg>()

    /** Naik setiap kali data paket berubah, dipakai layar untuk menghitung ulang daftar. */
    var version by mutableIntStateOf(0); private set
    val scans = mutableStateListOf<ScanRecord>()
    var lastResult by mutableStateOf<ScanResult?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var pendingImport by mutableStateOf<PendingImport?>(null); private set
    var loaded by mutableStateOf(false); private set

    private var lastCode = ""
    private var lastCodeAt = 0L
    private var lastAnyAt = 0L
    /** Mode "QR saja": abaikan barcode garis (dipakai bila barcode terlipat/rusak). */
    var qrOnly by mutableStateOf(false)
    private val resiLike = Regex("""^[A-Z]{1,5}\d{8,18}$""")

    val packages: Collection<Pkg> get() = pkgMap.values
    val packageCount get() = pkgMap.size

    private val loadJob = viewModelScope.launch {
        val (p, s) = withContext(Dispatchers.IO) { db.loadPackages() to db.loadScans() }
        pkgMap = p
        scans.addAll(s)
        if (p.isEmpty() && meta.fileName.isNotEmpty()) {
            meta = Meta()
            prefs.saveMeta(meta)
        }
        loaded = true
        version++
    }

    fun aging() = Aging(settings, LocalDate.now().toEpochDay())

    /** Hanya untuk screenshot dokumentasi: isi data contoh langsung ke memori (tidak ditulis ke DB). */
    @VisibleForTesting
    internal fun seedDemo(m: Meta, items: List<Pkg>, history: List<ScanRecord>, result: ScanResult?, pending: PendingImport? = null) {
        meta = m
        pkgMap = HashMap(items.associateBy { it.resi })
        scans.clear()
        scans.addAll(history)
        lastResult = result
        pendingImport = pending
        version++
    }

    /** Menunggu data awal dari DB selesai dimuat (dipakai tes). */
    @VisibleForTesting
    internal suspend fun awaitLoaded() = loadJob.join()

    fun consumeMessage() { message = null }

    // ---------- Scan ----------

    fun onCodes(codes: List<Code>) {
        if (!loaded) return
        val now = System.currentTimeMillis()
        val cands = codes.filter { !qrOnly || it.isQr }
            .flatMap { c -> Importer.resiCandidates(c.raw).map { it to c.isQr } }
        // Label bisa berisi beberapa barcode/QR: utamakan yang ada di data, lalu yang berpola resi.
        val chosen = cands.firstOrNull { pkgMap.containsKey(it.first) } ?: cands.firstOrNull { resiLike.matches(it.first) } ?: return
        val pick = chosen.first
        if (pick == lastCode && now - lastCodeAt < settings.cooldownSec * 1000L) {
            lastCodeAt = now // masih di depan kamera, jangan dihitung ulang
            return
        }
        if (now - lastAnyAt < 600) return
        lastCode = pick
        lastCodeAt = now
        lastAnyAt = now
        process(pick, now, if (chosen.second) Source.QR else Source.BARCODE)
    }

    fun checkManual(text: String) {
        val resi = Importer.normalizeResi(text)
        if (resi.isEmpty() || !loaded) return
        process(resi, System.currentTimeMillis(), Source.MANUAL)
    }

    private fun process(resi: String, now: Long, source: Source) {
        val a = aging()
        val p = pkgMap[resi]
        val age = p?.let(a::age)
        val level = if (p == null) Level.NOT_FOUND else a.level(age)
        val dup = if (p != null) p.scanCount > 0 else scans.any { it.resi == resi }
        val prevAt = p?.scannedAt ?: scans.firstOrNull { it.resi == resi }?.ts
        val rec = ScanRecord(0, resi, now, p != null, age, level, dup, source)
        val updated = p?.copy(scannedAt = p.scannedAt ?: now, scanCount = p.scanCount + 1)
        if (updated != null) pkgMap[resi] = updated
        scans.add(0, rec)
        lastResult = ScanResult(rec, p, prevAt)
        version++
        feedback.play(level, dup, settings)
        viewModelScope.launch(dbWriter) {
            db.insertScan(rec)
            if (updated != null) db.markScanned(resi, updated.scannedAt, updated.scanCount)
        }
    }

    /** Batalkan scan terakhir (mis. salah scan). */
    fun undoLast() {
        val rec = scans.firstOrNull() ?: return
        scans.removeAt(0)
        val p = pkgMap[rec.resi]
        val updated = p?.let {
            val n = (it.scanCount - 1).coerceAtLeast(0)
            it.copy(scanCount = n, scannedAt = if (n == 0) null else it.scannedAt)
        }
        if (updated != null) pkgMap[rec.resi] = updated
        if (lastResult?.rec === rec) lastResult = null
        lastCode = ""
        version++
        viewModelScope.launch(dbWriter) {
            db.deleteScan(rec.resi, rec.ts)
            if (updated != null) db.markScanned(rec.resi, updated.scannedAt, updated.scanCount)
        }
        message = "Scan ${rec.resi} dibatalkan"
    }

    fun scanStats(): ScanStats {
        val a = aging()
        var scanned = 0
        var crit = 0
        for (p in pkgMap.values) if (p.scanCount > 0) {
            scanned++
            if (a.level(p) == Level.CRIT) crit++
        }
        val notFound = scans.asSequence().filter { !it.found }.map { it.resi }.distinct().count()
        return ScanStats(pkgMap.size, scanned, crit, notFound)
    }

    fun resetSession() {
        viewModelScope.launch {
            withContext(dbWriter) { db.clearScans() }
            for ((k, v) in pkgMap) if (v.scanCount > 0) pkgMap[k] = v.copy(scannedAt = null, scanCount = 0)
            scans.clear()
            lastResult = null
            lastCode = ""
            version++
            message = "Sesi scan baru dimulai"
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(dbWriter) { db.clearAll() }
            pkgMap = HashMap()
            scans.clear()
            lastResult = null
            meta = Meta()
            prefs.saveMeta(meta)
            version++
            message = "Semua data dihapus"
        }
    }

    // ---------- Pengaturan ----------

    fun updateSettings(s: Settings) {
        settings = s
        prefs.saveSettings(s)
    }

    fun testSound(level: Level, dup: Boolean = false) = feedback.play(level, dup, settings.copy(sound = true, vibrate = true))

    // ---------- Import / Export ----------

    /** Langkah 1: baca file, tebak kolom, lalu tampilkan dialog pemetaan kolom. */
    fun requestImport(uri: Uri) {
        viewModelScope.launch {
            loadJob.join() // jangan timpa data lama sebelum selesai dimuat
            busy = "Membaca file…"
            try {
                val name = fileName(uri)
                val rows = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)!!.use {
                        if (name.endsWith(".csv", true) || name.endsWith(".txt", true)) CsvReader.read(it) else XlsxReader.read(it)
                    }
                }
                require(rows.isNotEmpty()) { "File kosong" }
                pendingImport = PendingImport(name, rows, Importer.guess(rows))
            } catch (e: Throwable) {
                message = if (e is OutOfMemoryError) "File terlalu besar untuk memori HP ini" else "Gagal membaca file: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    fun cancelImport() { pendingImport = null }

    /** Langkah 2: import dengan pemetaan kolom yang dipilih pengguna. */
    fun confirmImport(pi: PendingImport, cols: Columns, merge: Boolean) {
        pendingImport = null
        viewModelScope.launch {
            busy = "Mengimport ${fmt(pi.dataRows)} baris…"
            try {
                val doMerge = merge && pkgMap.isNotEmpty()
                val existing = if (doMerge) HashMap(pkgMap) else HashMap()
                val oldCols = meta.columns
                val (res, merged, finalCols) = withContext(dbWriter) {
                    val res = Importer.parse(pi.rows, cols, pi.guess.hasHeader, LocalDate.now())
                    require(res.items.isNotEmpty()) { "Tidak ada No. Resi yang terbaca di kolom \"${cols.name(cols.resi)}\"" }
                    var finalCols = cols
                    if (doMerge) {
                        val (union, mapOld, mapNew) = Importer.mergeColumns(oldCols, cols)
                        finalCols = union
                        for ((k, v) in existing) existing[k] = v.copy(values = mapOld(v.values))
                        for (p in res.items) {
                            val old = existing[p.resi]
                            val np = p.copy(values = mapNew(p.values))
                            existing[p.resi] = if (old != null) np.copy(scannedAt = old.scannedAt, scanCount = old.scanCount) else np
                        }
                    } else {
                        for (p in res.items) existing[p.resi] = p
                    }
                    db.writePackages(existing.values, clearScans = !doMerge)
                    Triple(res, existing, finalCols)
                }
                pkgMap = merged
                if (!doMerge) {
                    scans.clear()
                    lastResult = null
                }
                meta = Meta(pi.name, System.currentTimeMillis(), finalCols)
                prefs.saveMeta(meta)
                version++
                message = buildString {
                    append("Import selesai: ${fmt(res.items.size)} paket")
                    if (doMerge) append(" digabung (total ${fmt(merged.size)})")
                    if (res.dupInFile > 0) append(", ${fmt(res.dupInFile)} resi dobel")
                    if (res.badDates > 0) append(", ${fmt(res.badDates)} umur tak terbaca")
                }
            } catch (e: Throwable) {
                message = "Gagal import: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    fun suggestedExportName(): String =
        "Hasil Scan Umur " + SimpleDateFormat("dd-MM-yyyy HHmm", Locale.US).format(Date()) + ".xlsx"

    fun export(uri: Uri) {
        viewModelScope.launch {
            busy = "Membuat file Excel…"
            try {
                val pk = pkgMap.values.toList()
                val sc = scans.toList()
                val a = aging()
                val s = settings
                val m = meta
                withContext(Dispatchers.IO) {
                    val sheets = buildReport(pk, sc, a, s, m)
                    app.contentResolver.openOutputStream(uri)!!.use { XlsxWriter.write(it, sheets) }
                }
                message = "File Excel tersimpan"
            } catch (e: Throwable) {
                message = "Gagal export: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    private fun buildReport(pk: List<Pkg>, sc: List<ScanRecord>, a: Aging, s: Settings, m: Meta): List<XlsxWriter.Sheet> {
        val dt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
        val cols = m.columns
        val others = cols.others
        val otherNames = others.map { cols.name(it) }
        val byResi = pk.associateBy { it.resi }
        val sorted = pk.sortedWith(compareByDescending<Pkg> { a.age(it) ?: -1 }.thenBy { it.resi })
        val levels = pk.groupingBy { a.level(it) }.eachCount()
        val scanned = pk.count { it.scanCount > 0 }
        val notFound = sc.filter { !it.found }.map { it.resi }.distinct().size
        fun otherValues(p: Pkg?): List<Any?> = others.map { i -> p?.values?.getOrNull(i)?.ifBlank { null } }
        val wOthers = others.map { 16 }

        val summary = listOf(
            listOf("Keterangan", "Nilai"),
            listOf("File data", m.fileName.ifBlank { "-" }),
            listOf("Tanggal laporan", dt.format(Date())),
            listOf("Total paket di data", pk.size),
            listOf("Sudah discan", scanned),
            listOf("Belum discan", pk.size - scanned),
            listOf("Scan tidak ada di data", notFound),
            listOf("Total aktivitas scan", sc.size),
            listOf("Aman (< H+${s.warnDays})", levels[Level.OK] ?: 0),
            listOf("Perhatian (H+${s.warnDays} s/d H+${s.critDays - 1})", levels[Level.WARN] ?: 0),
            listOf("Kritis (≥ H+${s.critDays})", levels[Level.CRIT] ?: 0),
            listOf("Umur tidak diketahui", levels[Level.UNKNOWN] ?: 0),
            listOf("Kolom No. Resi", cols.name(cols.resi)),
            listOf("Kolom dasar umur", cols.age.joinToString(", ") { cols.name(it) }.ifBlank { "-" }),
        )
        val scanRows = ArrayList<List<Any?>>()
        scanRows.add(listOf("No", "Waktu Scan", cols.name(cols.resi), "Status Umur", "Umur (hari)") + otherNames + listOf("Sumber", "Keterangan"))
        sc.asReversed().forEachIndexed { i, r ->
            val p = byResi[r.resi]
            scanRows.add(
                listOf<Any?>(i + 1, dt.format(Date(r.ts)), r.resi, r.level.label, r.age) + otherValues(p) +
                    listOf(r.source.label, if (r.dup) "Scan ulang" else null),
            )
        }
        val missing = ArrayList<List<Any?>>()
        missing.add(listOf("No", cols.name(cols.resi), "Umur (hari)", "Status Umur") + otherNames)
        sorted.filter { it.scanCount == 0 }.forEachIndexed { i, p ->
            val age = a.age(p)
            missing.add(listOf<Any?>(i + 1, p.resi, age, a.level(age).label) + otherValues(p))
        }
        val all = ArrayList<List<Any?>>()
        all.add(cols.names + listOf("Umur (hari)", "Status Umur", "Sudah Discan", "Waktu Scan Pertama"))
        for (p in sorted) {
            val age = a.age(p)
            all.add(
                cols.names.indices.map { i -> if (i == cols.resi) p.resi else p.values.getOrNull(i)?.ifBlank { null } } +
                    listOf(age, a.level(age).label, if (p.scanCount > 0) "Ya" else "Tidak", p.scannedAt?.let { dt.format(Date(it)) }),
            )
        }
        return listOf(
            XlsxWriter.Sheet("Ringkasan", summary, listOf(36, 28)),
            XlsxWriter.Sheet("Hasil Scan", scanRows, listOf(6, 20, 18, 18, 11) + wOthers + listOf(10, 12)),
            XlsxWriter.Sheet("Belum Discan", missing, listOf(6, 18, 11, 18) + wOthers),
            XlsxWriter.Sheet("Semua Data", all, cols.names.map { 16 } + listOf(11, 18, 13, 20)),
        )
    }

    private fun fileName(uri: Uri): String {
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: "data.xlsx"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "data.xlsx"
    }

    override fun onCleared() {
        feedback.release()
        db.close()
    }
}

private val idLocale: Locale = Locale.forLanguageTag("id-ID")
fun fmt(n: Int): String = String.format(idLocale, "%,d", n)
