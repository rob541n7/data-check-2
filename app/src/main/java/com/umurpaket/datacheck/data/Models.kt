package com.umurpaket.datacheck.data

import android.content.Context

enum class Source(val label: String) { BARCODE("Barcode"), QR("QR"), MANUAL("Ketik") }

/** Hasil baca kamera: isi kode + apakah berasal dari QR. */
data class Code(val raw: String, val isQr: Boolean)

enum class Level(val label: String) {
    OK("AMAN"),
    WARN("PERHATIAN"),
    CRIT("KRITIS"),
    UNKNOWN("UMUR ?"),
    NOT_FOUND("TIDAK ADA DI DATA"),
}

/**
 * Satu baris paket. [values] berisi SEMUA kolom file apa adanya (urut sesuai [Columns.names]),
 * jadi nama & jumlah kolom bebas. [baseDay] = tanggal acuan umur (epochDay), dihitung saat import.
 */
data class Pkg(
    val resi: String,
    val values: List<String>,
    val baseDay: Long?,
    val attempt: Int?,
    val scannedAt: Long? = null,
    val scanCount: Int = 0,
)

/**
 * Susunan kolom file + peran kolom yang dipakai aplikasi.
 * [age] = kolom dasar umur, urut prioritas (kolom berikutnya dipakai bila yang pertama kosong).
 * Berisi angka (mis. 5) = umur hari saat import; berisi tanggal = umur dihitung sejak tanggal itu.
 */
data class Columns(
    val names: List<String>,
    val resi: Int,
    val age: List<Int>,
    val attempt: Int = -1,
) {
    /** Kolom selain No. Resi, untuk ditampilkan. */
    val others: List<Int> get() = names.indices.filter { it != resi }

    fun name(i: Int) = names.getOrNull(i) ?: "-"

    companion object {
        val DEFAULT = Columns(listOf("No. Resi", "Tgl Pickup", "Tgl Sampai", "Delivery Attempt"), 0, listOf(2, 1), 3)
    }
}

data class ScanRecord(
    val id: Long,
    val resi: String,
    val ts: Long,
    val found: Boolean,
    val age: Int?,
    val level: Level,
    val dup: Boolean,
    val source: Source = Source.BARCODE,
)

data class ScanResult(val rec: ScanRecord, val pkg: Pkg?, val prevAt: Long?)

data class Settings(
    val warnDays: Int = 3,
    val critDays: Int = 5,
    val attemptWarn: Int = 3,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val cooldownSec: Int = 3,
)

data class Meta(val fileName: String = "", val importedAt: Long = 0L, val columns: Columns = Columns.DEFAULT)

/** Menghitung umur (H+) dan level warna berdasarkan pengaturan. */
class Aging(private val s: Settings, val today: Long) {
    fun age(p: Pkg): Int? = p.baseDay?.let { (today - it).toInt().coerceAtLeast(0) }

    fun level(age: Int?): Level = when {
        age == null -> Level.UNKNOWN
        age >= s.critDays -> Level.CRIT
        age >= s.warnDays -> Level.WARN
        else -> Level.OK
    }

    fun level(p: Pkg): Level = level(age(p))

    fun highAttempt(p: Pkg?): Boolean = (p?.attempt ?: 0) >= s.attemptWarn
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("datacheck", Context.MODE_PRIVATE)

    fun loadSettings() = Settings(
        warnDays = sp.getInt("warn", 3),
        critDays = sp.getInt("crit", 5),
        attemptWarn = sp.getInt("attempt", 3),
        sound = sp.getBoolean("sound", true),
        vibrate = sp.getBoolean("vibrate", true),
        cooldownSec = sp.getInt("cooldown", 3),
    )

    fun saveSettings(s: Settings) = sp.edit()
        .putInt("warn", s.warnDays).putInt("crit", s.critDays).putInt("attempt", s.attemptWarn)
        .putBoolean("sound", s.sound).putBoolean("vibrate", s.vibrate).putInt("cooldown", s.cooldownSec)
        .apply()

    fun loadMeta(): Meta {
        val names = sp.getString("colNames", null)?.split(SEP)
        val cols = if (names == null) Columns.DEFAULT else Columns(
            names,
            sp.getInt("colResi", 0),
            sp.getString("colAge", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() },
            sp.getInt("colAttempt", -1),
        )
        return Meta(sp.getString("file", "") ?: "", sp.getLong("importedAt", 0L), cols)
    }

    fun saveMeta(m: Meta) = sp.edit()
        .putString("file", m.fileName).putLong("importedAt", m.importedAt)
        .putString("colNames", m.columns.names.joinToString(SEP)).putInt("colResi", m.columns.resi)
        .putString("colAge", m.columns.age.joinToString(",")).putInt("colAttempt", m.columns.attempt)
        .apply()

    companion object {
        const val SEP = "\u001F"
    }
}
