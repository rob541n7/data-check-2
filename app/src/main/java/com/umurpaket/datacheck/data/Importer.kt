package com.umurpaket.datacheck.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.floor

sealed interface CellDate {
    data class Day(val epochDay: Long) : CellDate
    /** Kolom berisi angka kecil (mis. 5, "5 hari", "H+5") = umur yang sudah dihitung di file. */
    data class Age(val days: Int) : CellDate
}

val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Membaca semua format tanggal/umur yang ditemukan di file scan:
 * 2026-01-12, 22/08/2026, 9/08/2026, 03-02-2026, 09-01 (tanpa tahun),
 * serial Excel (46034 / 46076.89), 15 Jan 2026, dan umur (5, "5 hari", "H+5").
 */
object Dates {
    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mei" to 5, "may" to 5, "jun" to 6,
        "jul" to 7, "agu" to 8, "agt" to 8, "ags" to 8, "aug" to 8, "sep" to 9, "okt" to 10,
        "oct" to 10, "nov" to 11, "des" to 12, "dec" to 12,
    )
    private val numeric = Regex("""^\d+(\.\d+)?$""")
    private val ageText = Regex("""^(?:H\s*\+\s*)?(\d{1,3})\s*(?:hari|hr|h|d|day|days)?$""", RegexOption.IGNORE_CASE)
    private val ymd = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val dmy = Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""")
    private val dm = Regex("""^(\d{1,2})[-/.](\d{1,2})$""")
    private val dMonY = Regex("""^(\d{1,2})[\s\-/]+([A-Za-z]{3,})[\s\-/]*(\d{2,4})?""")

    fun parse(raw: String?, today: LocalDate): CellDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (numeric.matches(s)) {
            val n = s.toDouble()
            return when {
                n <= 999.0 -> CellDate.Age(n.toInt())
                n in 20000.0..80000.0 -> CellDate.Day(floor(n).toLong() - 25569L)
                else -> null
            }
        }
        ageText.matchEntire(s)?.let { return CellDate.Age(it.groupValues[1].toInt()) }
        ymd.find(s)?.let { m ->
            return day(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        dmy.find(s)?.let { m ->
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            return day(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        dm.find(s)?.let { m ->
            return withoutYear(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)
        }
        dMonY.find(s)?.let { m ->
            val mon = months[m.groupValues[2].take(3).lowercase()] ?: return null
            val d = m.groupValues[1].toInt()
            val yTxt = m.groupValues[3]
            if (yTxt.isEmpty()) return withoutYear(d, mon, today)
            var y = yTxt.toInt()
            if (y < 100) y += 2000
            return day(y, mon, d)
        }
        return null
    }

    private fun day(y: Int, m: Int, d: Int): CellDate? {
        // format bulan/hari (gaya US) bila bulan > 12
        val (mm, dd) = if (m > 12 && d <= 12) d to m else m to d
        return runCatching { CellDate.Day(LocalDate.of(y, mm, dd).toEpochDay()) }.getOrNull()
    }

    private fun withoutYear(d: Int, m: Int, today: LocalDate): CellDate? {
        val date = runCatching { LocalDate.of(today.year, m, d) }.getOrNull() ?: return null
        val fixed = if (date.isAfter(today.plusDays(31))) date.minusYears(1) else date
        return CellDate.Day(fixed.toEpochDay())
    }
}

data class ImportResult(
    val items: List<Pkg>,
    val rowsRead: Int,
    val dupInFile: Int,
    val badDates: Int,
)

/** Hasil tebakan kolom dari baris judul file. */
data class ColumnGuess(val columns: Columns, val hasHeader: Boolean)

object Importer {
    private val space = Regex("""\s+""")
    private val resiLike = Regex("""^[A-Z]{1,5}\d{8,18}$""")

    fun normalizeResi(s: String): String = s.replace(space, "").uppercase()

    private val resiInText = Regex("""(?<![A-Z0-9])[A-Z]{1,5}\d{8,18}(?![0-9])""")

    /**
     * Kandidat No. Resi dari isi barcode/QR. QR label J&T berisi resi saja,
     * tapi QR lain bisa berisi link/teks yang memuat resi di dalamnya.
     */
    fun resiCandidates(raw: String): List<String> {
        val whole = normalizeResi(raw)
        val found = resiInText.findAll(raw.uppercase()).map { it.value }.toList()
        return (listOf(whole) + found).filter { it.isNotEmpty() }.distinct()
    }

    private val resiKeys = listOf("resi", "waybill", "awb", "no kirim", "nomor kirim", "tracking", "no. kirim")
    private val ageKeys = listOf("umur", "aging", "age", "lama", "durasi")
    private val arriveKeys = listOf("sampai", "arrived", "arrival", "tiba", "datang", "masuk", "inbound")
    private val pickupKeys = listOf("pickup", "pick up", "diambil")
    private val dateKeys = listOf("tgl", "tanggal", "date", "waktu")
    private val attemptKeys = listOf("attempt", "percobaan", "antar ke")

    private fun colLetter(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { sb.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** Tebak peran kolom dari nama judulnya (bebas: No Waybill, Umur Paket, Status Longtail, dst). */
    fun guess(rows: List<List<String>>): ColumnGuess {
        val first = rows.firstOrNull().orEmpty()
        val hasHeader = first.any { it.isNotBlank() } && first.none { resiLike.matches(normalizeResi(it)) }
        val width = rows.take(200).maxOfOrNull { it.size } ?: 0
        val names = List(width) { i ->
            val h = if (hasHeader) first.getOrNull(i)?.trim().orEmpty() else ""
            h.ifBlank { "Kolom ${colLetter(i)}" }
        }
        val low = names.map { it.lowercase() }
        fun find(keys: List<String>) = low.indices.filter { i -> keys.any { low[i].contains(it) } }
        val data = rows.drop(if (hasHeader) 1 else 0).take(200)

        val resi = find(resiKeys).firstOrNull()
            ?: names.indices.maxByOrNull { c -> data.count { resiLike.matches(normalizeResi(it.getOrNull(c).orEmpty())) } }
            ?: 0
        val attempt = find(attemptKeys).firstOrNull { it != resi } ?: -1
        val today = LocalDate.now()
        val age = (find(ageKeys) + find(arriveKeys) + find(pickupKeys) + find(dateKeys))
            .distinct().filter { it != resi && it != attempt }
            .ifEmpty {
                // tidak ada judul yang cocok: pakai kolom yang isinya mayoritas tanggal
                names.indices.filter { c ->
                    c != resi && c != attempt &&
                        data.count { Dates.parse(it.getOrNull(c), today) is CellDate.Day } > data.size / 2
                }
            }
        return ColumnGuess(Columns(names, resi, age, attempt), hasHeader)
    }

    fun parse(rows: List<List<String>>, cols: Columns, hasHeader: Boolean, today: LocalDate): ImportResult {
        val todayDay = today.toEpochDay()
        val width = cols.names.size
        val seen = LinkedHashMap<String, Pkg>(rows.size * 2)
        var read = 0
        var dup = 0
        var bad = 0
        for ((i, row) in rows.withIndex()) {
            if (hasHeader && i == 0) continue
            val resi = normalizeResi(row.getOrNull(cols.resi).orEmpty())
            if (resi.isEmpty()) continue
            read++
            val values = List(width) { row.getOrNull(it)?.trim().orEmpty() }
            var base: Long? = null
            var anyAgeText = false
            for (c in cols.age) {
                val raw = values.getOrNull(c).orEmpty()
                if (raw.isEmpty()) continue
                anyAgeText = true
                base = toDay(Dates.parse(raw, today), todayDay)
                if (base != null) break
            }
            if (anyAgeText && base == null) bad++
            val attempt = if (cols.attempt >= 0) values.getOrNull(cols.attempt)?.toDoubleOrNull()?.toInt() else null
            if (seen.put(resi, Pkg(resi, values, base, attempt)) != null) dup++
        }
        return ImportResult(seen.values.toList(), read, dup, bad)
    }

    private fun toDay(v: CellDate?, todayDay: Long): Long? = when (v) {
        is CellDate.Day -> v.epochDay
        is CellDate.Age -> todayDay - v.days
        null -> null
    }

    /**
     * Gabungkan susunan kolom lama & baru (untuk mode "Gabungkan" dengan file berbeda kolom).
     * Hasil: kolom gabungan + fungsi pemetaan nilai paket lama / baru ke kolom gabungan.
     */
    fun mergeColumns(old: Columns, new: Columns): Triple<Columns, (List<String>) -> List<String>, (List<String>) -> List<String>> {
        val union = old.names.toMutableList()
        val newIdx = new.names.map { n ->
            val i = union.indexOf(n)
            if (i >= 0) i else { union.add(n); union.size - 1 }
        }
        val cols = Columns(
            union,
            newIdx[new.resi],
            new.age.map { newIdx[it] },
            if (new.attempt >= 0) newIdx[new.attempt] else -1,
        )
        val mapOld: (List<String>) -> List<String> = { v -> List(union.size) { v.getOrNull(it).orEmpty() } }
        val mapNew: (List<String>) -> List<String> = { v ->
            val out = MutableList(union.size) { "" }
            v.forEachIndexed { i, s -> out[newIdx[i]] = s }
            out
        }
        return Triple(cols, mapOld, mapNew)
    }
}
