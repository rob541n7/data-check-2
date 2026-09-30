package com.umurpaket.datacheck.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Pembaca .xlsx ringan (sheet pertama) tanpa library besar.
 * File disalin ke file sementara lalu dibaca per bagian (streaming): isi sheet tidak pernah dimuat utuh
 * ke memori, jadi file dengan format sampai jutaan baris kosong atau banyak sheet tidak membuat HP kehabisan memori.
 */
object XlsxReader {
    /** Batas pengaman jumlah baris berisi data. */
    const val MAX_ROWS = 300_000
    const val MAX_FILE_BYTES = 100_000_000L

    fun read(input: InputStream): List<List<String>> {
        val tmp = File.createTempFile("import", ".xlsx")
        try {
            // salin dengan batas ukuran: sumber file yang tidak wajar tidak boleh memenuhi memori/penyimpanan
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_FILE_BYTES) { "File terlalu besar (lebih dari ${MAX_FILE_BYTES / 1_000_000} MB)" }
                    out.write(buf, 0, n)
                }
            }
            val zip = try {
                ZipFile(tmp)
            } catch (e: Exception) {
                throw IllegalArgumentException("File bukan .xlsx yang valid (simpan ulang sebagai Excel Workbook .xlsx)")
            }
            zip.use { z ->
                val names = z.entries().asSequence().map { it.name }.toList()
                fun entry(n: String): ZipEntry? = z.getEntry(n) ?: z.getEntry("/$n")
                fun <T> open(n: String, f: (InputStream) -> T): T? = entry(n)?.let { e -> z.getInputStream(e).use { f(BufferedInputStream(it)) } }
                val sheets = names.map { it.trimStart('/') }.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                require(sheets.isNotEmpty()) { "File bukan .xlsx yang valid (simpan ulang sebagai Excel Workbook .xlsx)" }
                val shared = open("xl/sharedStrings.xml", ::parseShared).orEmpty()
                val dateStyles = open("xl/styles.xml", ::parseDateStyles).orEmpty()
                val rid = open("xl/workbook.xml", ::firstSheetRid)
                val path = rid?.let { r -> open("xl/_rels/workbook.xml.rels") { relTarget(it, r) } }?.takeIf { entry(it) != null }
                    ?: sheets.minBy { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
                return open(path) { parseSheet(it, shared, dateStyles) }.orEmpty()
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Indeks style (atribut s pada sel) yang berformat tanggal; nilai true bila formatnya juga memuat jam.
     * Tanpa ini, sel tanggal Excel hanya terbaca sebagai angka seperti 46095.
     */
    private fun parseDateStyles(input: InputStream): Map<Int, Boolean> {
        val custom = HashMap<Int, String>()
        val xfs = ArrayList<Int>()
        val p = parser(input)
        var inXfs = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.local) {
                "numFmt" -> p.getAttributeValue(null, "numFmtId")?.toIntOrNull()?.let { custom[it] = p.getAttributeValue(null, "formatCode").orEmpty() }
                "cellXfs" -> inXfs = true
                "xf" -> if (inXfs) xfs.add(p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
            } else if (p.eventType == XmlPullParser.END_TAG && p.local == "cellXfs") inXfs = false
        }
        val out = HashMap<Int, Boolean>()
        xfs.forEachIndexed { i, id ->
            val code = custom[id]
            if (code != null) {
                val clean = code.replace(dateJunk, "").lowercase()
                if (clean.contains('y') || clean.contains('d')) out[i] = clean.contains('h')
            } else when (id) {
                in 14..17, in 27..31, in 34..36, in 50..58 -> out[i] = false
                22 -> out[i] = true
            }
        }
        return out
    }

    /** Teks dalam kutip, [warna]/[$-locale], dan karakter escape di kode format Excel diabaikan. */
    private val dateJunk = Regex(""""[^"]*"|\[[^\]]*\]|\\.""")

    private fun excelDate(serial: String, withTime: Boolean): String? {
        val n = serial.toDoubleOrNull() ?: return null
        if (n < 1 || n > 100000) return null
        val whole = kotlin.math.floor(n)
        val date = java.time.LocalDate.of(1899, 12, 30).plusDays(whole.toLong())
        val secs = Math.round((n - whole) * 86400)
        val d = "%02d/%02d/%04d".format(date.dayOfMonth, date.monthValue, date.year)
        return if (withTime && secs > 0) d + " %02d:%02d".format(secs / 3600, secs % 3600 / 60) else d
    }

    private fun parser(input: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply { setInput(input, null) }

    private val XmlPullParser.local get() = name.substringAfter(':')

    /** r:id sheet pertama di workbook.xml. */
    private fun firstSheetRid(input: InputStream): String? {
        val p = parser(input)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.local == "sheet") {
                for (i in 0 until p.attributeCount) {
                    if (p.getAttributeName(i).substringAfter(':') == "id") return p.getAttributeValue(i)
                }
            }
        }
        return null
    }

    /** Path file sheet untuk r:id tertentu, dari workbook.xml.rels. */
    private fun relTarget(input: InputStream, rid: String): String? {
        val r = parser(input)
        while (r.next() != XmlPullParser.END_DOCUMENT) {
            if (r.eventType == XmlPullParser.START_TAG && r.local == "Relationship" &&
                r.getAttributeValue(null, "Id") == rid
            ) {
                val t = r.getAttributeValue(null, "Target") ?: return null
                return if (t.startsWith("/")) t.trimStart('/') else "xl/$t"
            }
        }
        return null
    }

    private fun parseShared(input: InputStream): List<String> {
        val out = ArrayList<String>()
        val p = parser(input)
        val sb = StringBuilder()
        var inT = false
        var skip = 0 // di dalam <rPh> (teks fonetik) diabaikan
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "si" -> sb.setLength(0)
                    "rPh" -> skip++
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT && skip == 0) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.local) {
                    "si" -> out.add(sb.toString())
                    "rPh" -> skip--
                    "t" -> inT = false
                }
            }
        }
        return out
    }

    private fun colIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var n = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }

    /** Batas kolom yang dibaca; sel berformat di kolom jauh (mis. XFD) tidak boleh membuat baris raksasa. */
    private const val MAX_COLS = 256

    private fun parseSheet(input: InputStream, shared: List<String>, dateStyles: Map<Int, Boolean>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = parser(input)
        val row = HashMap<Int, String>()
        var nextCol = 0
        var col = 0
        var type: String? = null
        var style = 0
        val value = StringBuilder()
        var capture = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "row" -> { row.clear(); nextCol = 0 }
                    "c" -> {
                        val ci = colIndex(p.getAttributeValue(null, "r"))
                        col = if (ci >= 0) ci else nextCol
                        nextCol = col + 1
                        type = p.getAttributeValue(null, "t")
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
                XmlPullParser.TEXT -> if (capture) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.local) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val v = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            null, "n" -> dateStyles[style]?.let { excelDate(raw, it) } ?: raw
                            else -> raw
                        }
                        if (col < MAX_COLS && v.isNotBlank()) row[col] = v
                    }
                    "row" -> if (row.isNotEmpty()) {
                        val max = row.keys.max()
                        rows.add(List(max + 1) { row[it].orEmpty() })
                        require(rows.size <= MAX_ROWS) {
                            "File terlalu besar (lebih dari ${MAX_ROWS / 1000} ribu baris). Pecah file per DP dulu."
                        }
                    }
                }
            }
        }
        return rows
    }
}

/** CSV sederhana (pemisah , ; atau tab, mendukung tanda kutip). */
object CsvReader {
    fun read(input: InputStream): List<List<String>> {
        val lines = input.bufferedReader(Charsets.UTF_8).readLines().map { it.removePrefix("﻿") }
        val first = lines.firstOrNull().orEmpty()
        val sep = listOf('\t', ';', ',').maxBy { c -> first.count { it == c } }
        return lines.filter { it.isNotBlank() }.map { split(it, sep) }
    }

    private fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == sep && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

/** Penulis .xlsx minimal: beberapa sheet, header tebal, baris header dibekukan. */
object XlsxWriter {
    class Sheet(val name: String, val rows: List<List<Any?>>, val widths: List<Int> = emptyList())

    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            val n = sheets.size
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                    (1..n).joinToString("") { """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
                    "</Types>",
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            )
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                    sheets.mapIndexed { i, s -> """<sheet name="${esc(sheetName(s.name))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") +
                    "</sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    (1..n).joinToString("") { """<Relationship Id="rId$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$it.xml"/>""" } +
                    """<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            )
            put(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F3A93"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
            )
            sheets.forEachIndexed { i, s ->
                z.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
                val w = z.bufferedWriter(Charsets.UTF_8)
                writeSheet(w, s)
                w.flush()
                z.closeEntry()
            }
        }
    }

    private fun writeSheet(w: java.io.Writer, s: Sheet) {
        w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        w.write("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (s.widths.isNotEmpty()) {
            w.write("<cols>")
            s.widths.forEachIndexed { i, wd -> w.write("""<col min="${i + 1}" max="${i + 1}" width="$wd" customWidth="1"/>""") }
            w.write("</cols>")
        }
        w.write("<sheetData>")
        s.rows.forEachIndexed { r, row ->
            w.write("""<row r="${r + 1}">""")
            row.forEachIndexed { c, v ->
                if (v == null || v == "") return@forEachIndexed
                val ref = colName(c) + (r + 1)
                val style = if (r == 0) """ s="1"""" else ""
                if (v is Number) w.write("""<c r="$ref"$style><v>$v</v></c>""")
                else w.write("""<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
            }
            w.write("</row>")
        }
        w.write("</sheetData></worksheet>")
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, 'A' + m)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun sheetName(s: String) = s.replace(Regex("""[\[\]:*?/\\]"""), " ").take(31)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}
