package com.umurpaket.datacheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

class ImportTest {
    private val today = LocalDate.of(2026, 9, 24)
    private val t = today.toEpochDay()
    private fun day(y: Int, m: Int, d: Int) = CellDate.Day(LocalDate.of(y, m, d).toEpochDay())

    private fun load(name: String): Pair<ColumnGuess, ImportResult> {
        val rows = javaClass.classLoader!!.getResourceAsStream(name)!!.use { XlsxReader.read(it) }
        val g = Importer.guess(rows)
        return g to Importer.parse(rows, g.columns, g.hasHeader, today)
    }

    @Test
    fun dateFormats() {
        assertEquals(day(2026, 1, 12), Dates.parse("2026-01-12", today))
        assertEquals(day(2026, 8, 22), Dates.parse("22/08/2026", today))
        assertEquals(day(2026, 8, 9), Dates.parse("9/08/2026", today))
        assertEquals(day(2026, 2, 3), Dates.parse("03-02-2026", today))
        assertEquals(day(2026, 1, 9), Dates.parse("09-01", today))
        assertEquals(day(2025, 12, 30), Dates.parse("30-12", LocalDate.of(2026, 1, 5)))
        assertEquals(day(2026, 3, 14), Dates.parse("46095", today)) // serial Excel
        assertEquals(day(2026, 2, 23), Dates.parse("46076.89143518518", today))
        assertEquals(day(2026, 1, 15), Dates.parse("15 Jan 2026", today))
        assertEquals(day(2026, 8, 17), Dates.parse("17 Agustus 2026", today))
        assertEquals(day(2026, 8, 20), Dates.parse("2026-08-20 12:43:50", today))
        assertEquals(day(2026, 9, 20), Dates.parse("20/09/2026 14:30", today))
        assertEquals(CellDate.Age(5), Dates.parse("5", today))
        assertEquals(CellDate.Age(5), Dates.parse("5 hari", today))
        assertEquals(CellDate.Age(9), Dates.parse("H+9", today))
        assertNull(Dates.parse("#N/A", today))
        assertNull(Dates.parse("", today))
    }

    @Test
    fun agingLevels() {
        val p = Pkg("JX1", listOf("JX1"), baseDay = t - 4, attempt = 3)
        val a = Aging(Settings(), t)
        assertEquals(4, a.age(p))
        assertEquals(Level.WARN, a.level(p))
        assertEquals(Level.CRIT, a.level(p.copy(baseDay = t - 9)))
        assertEquals(Level.OK, a.level(2))
        assertEquals(Level.UNKNOWN, a.level(p.copy(baseDay = null)))
        assertTrue(a.highAttempt(p))
    }

    /** Format: No Waybill, Umur Paket, Delivery Attempt, Status Scan Terakhir */
    @Test
    fun flexibleWaybill() {
        val (g, res) = load("contoh_waybill.xlsx")
        val c = g.columns
        assertEquals(listOf("No Waybill", "Umur Paket", "Delivery Attempt", "Status Scan Terakhir"), c.names)
        assertEquals(0, c.resi)
        assertEquals(listOf(1), c.age)
        assertEquals(2, c.attempt)
        val p = res.items.first { it.resi == "JX1000000001" }
        assertEquals(7, Aging(Settings(), t).age(p))
        assertEquals(2, p.attempt)
        assertEquals("Scan Kirim", p.values[3])
        assertEquals(3, res.items.size)
    }

    /** Format: No Resi, Umur Paket, Delivery Attempt, Status Longtail, Tgl Masuk (sel tanggal Excel) */
    @Test
    fun flexibleLongtail() {
        val (g, res) = load("contoh_longtail.xlsx")
        val c = g.columns
        assertEquals("No Resi", c.name(c.resi))
        assertEquals(listOf(1, 4), c.age) // Umur Paket dulu, Tgl Masuk cadangan
        val a = Aging(Settings(), t)
        val byResi = res.items.associateBy { it.resi }
        assertEquals(5, a.age(byResi.getValue("JX1000000004"))) // "5 hari"
        assertEquals(9, a.age(byResi.getValue("JX1000000005"))) // "H+9"
        assertEquals(2, a.age(byResi.getValue("JX1000000006"))) // umur kosong -> Tgl Masuk 22/09
        assertEquals("20/09/2026 14:30", byResi.getValue("JX1000000004").values[4]) // tanggal Excel terbaca sebagai tanggal
        assertEquals("15/09/2026", byResi.getValue("JX1000000005").values[4])
        assertEquals("Longtail", byResi.getValue("JX1000000004").values[3])
    }

    @Test
    fun noHeaderFile() {
        val rows = listOf(listOf("JX1234567890", "3", "1"), listOf("JX1234567891", "8", "2"))
        val g = Importer.guess(rows)
        assertEquals(false, g.hasHeader)
        assertEquals(0, g.columns.resi)
        assertEquals(2, Importer.parse(rows, g.columns, g.hasHeader, today).items.size)
    }

    @Test
    fun mergeDifferentColumns() {
        val old = Columns(listOf("No Resi", "Umur Paket", "Status Longtail"), 0, listOf(1), -1)
        val new = Columns(listOf("No Waybill", "Umur Paket", "Delivery Attempt"), 0, listOf(1), 2)
        val (u, mapOld, mapNew) = Importer.mergeColumns(old, new)
        assertEquals(listOf("No Resi", "Umur Paket", "Status Longtail", "No Waybill", "Delivery Attempt"), u.names)
        assertEquals(listOf("JX1", "3", "Longtail", "", ""), mapOld(listOf("JX1", "3", "Longtail")))
        assertEquals(listOf("", "4", "", "JX2", "1"), mapNew(listOf("JX2", "4", "1")))
        assertEquals(3, u.resi)
        assertEquals(4, u.attempt)
    }

    /** Semua file scan asli milik pengguna tetap terbaca dengan deteksi kolom otomatis. */
    @Test
    fun realFiles() {
        val dir = File("D:/APP Umur Paket")
        if (!dir.exists()) return
        val files = dir.listFiles { f -> f.name.endsWith(".xlsx") && !f.name.startsWith("~$") && !f.name.startsWith("Contoh") }!!.sortedBy { it.name }
        for (f in files) {
            val rows = f.inputStream().use { XlsxReader.read(it) }
            val g = Importer.guess(rows)
            val res = Importer.parse(rows, g.columns, g.hasHeader, today)
            val c = g.columns
            val first = res.items.first()
            println(
                "%-46s paket=%-6d umurGagal=%-4d resi=%s umur=%s attempt=%s | contoh %s: %s".format(
                    f.name, res.items.size, res.badDates, c.name(c.resi), c.age.map { c.name(it) }, c.name(c.attempt),
                    first.resi, c.names.indices.joinToString(", ") { "${c.name(it)}=${first.values[it]}" },
                ),
            )
            assertTrue(f.name, res.items.isNotEmpty())
            assertEquals(f.name, 0, res.badDates)
        }
    }

    /** Cek sekumpulan file dengan mesin import aplikasi: CHECK_DIR=folder. */
    @Test
    fun checkFolder() {
        val dir = System.getenv("CHECK_DIR")?.let(::File) ?: return
        val a = Aging(Settings(), t)
        for (f in dir.listFiles { x -> x.name.endsWith(".xlsx") && !x.name.startsWith("~$") }!!.sortedBy { it.name }) {
            val rows = f.inputStream().use { XlsxReader.read(it) }
            val g = Importer.guess(rows)
            val c = g.columns
            val res = Importer.parse(rows, c, g.hasHeader, today)
            val p = res.items.first()
            println(
                "CHECK %-45s paket=%-4d dobel=%d umurGagal=%d | resi=%s umur=%s attempt=%s | %s -> H+%s, attempt=%s, level=%s".format(
                    f.name, res.items.size, res.dupInFile, res.badDates, c.name(c.resi), c.age.map { c.name(it) }, c.name(c.attempt),
                    p.resi, a.age(p), p.attempt, a.level(p),
                ),
            )
        }
    }

    @Test
    fun writerRoundTrip() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(
            out,
            listOf(
                XlsxWriter.Sheet("Data", listOf(listOf("No. Resi", "Tgl Pickup", "Tgl Sampai", "Delivery Attempt"), listOf("JX123", null, "22/08/2026", 3), listOf("JX<&>\"9", "5", "", 0))),
                XlsxWriter.Sheet("Kedua", listOf(listOf("a"))),
            ),
        )
        val rows = XlsxReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("No. Resi", "Tgl Pickup", "Tgl Sampai", "Delivery Attempt"), rows[0])
        assertEquals(listOf("JX123", "", "22/08/2026", "3"), rows[1])
        assertEquals(listOf("JX<&>\"9", "5", "", "0"), rows[2])
    }

    @Test
    fun csv() {
        val rows = CsvReader.read(ByteArrayInputStream("No. Resi;Tgl Sampai\nJX1;\"22/08/2026\"\n".toByteArray()))
        assertEquals(listOf("JX1", "22/08/2026"), rows[1])
    }
}
