package com.umurpaket.datacheck.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "datacheck2.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        createPackages(db)
        db.execSQL(
            """CREATE TABLE scans(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                resi TEXT NOT NULL, ts INTEGER NOT NULL, found INTEGER NOT NULL,
                age INTEGER, level TEXT NOT NULL, dup INTEGER NOT NULL, source TEXT)""",
        )
    }

    /** vals = semua kolom file (dipisah karakter \u001F), apa pun nama kolomnya. */
    private fun createPackages(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE packages(
                resi TEXT PRIMARY KEY, vals TEXT NOT NULL, base_day INTEGER, attempt INTEGER,
                scanned_at INTEGER, scan_count INTEGER NOT NULL DEFAULT 0)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE scans ADD COLUMN source TEXT")
        if (oldVersion < 3) {
            // format kolom tetap (v2) diganti kolom bebas: data paket perlu diimport ulang
            db.execSQL("DROP TABLE IF EXISTS packages")
            createPackages(db)
            db.delete("scans", null, null)
        }
    }

    private fun Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)
    private fun Cursor.intOrNull(i: Int) = if (isNull(i)) null else getInt(i)

    fun loadPackages(): HashMap<String, Pkg> {
        val out = HashMap<String, Pkg>()
        readableDatabase.rawQuery("SELECT resi,vals,base_day,attempt,scanned_at,scan_count FROM packages", null).use { c ->
            while (c.moveToNext()) {
                val p = Pkg(
                    c.getString(0), c.getString(1).split(Prefs.SEP), c.longOrNull(2), c.intOrNull(3),
                    c.longOrNull(4), c.getInt(5),
                )
                out[p.resi] = p
            }
        }
        return out
    }

    fun loadScans(): List<ScanRecord> {
        val out = ArrayList<ScanRecord>()
        readableDatabase.rawQuery("SELECT id,resi,ts,found,age,level,dup,source FROM scans ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ScanRecord(
                        c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) == 1, c.intOrNull(4),
                        runCatching { Level.valueOf(c.getString(5)) }.getOrDefault(Level.UNKNOWN), c.getInt(6) == 1,
                        c.getString(7)?.let { s -> runCatching { Source.valueOf(s) }.getOrNull() } ?: Source.BARCODE,
                    ),
                )
            }
        }
        return out
    }

    /** Tulis ulang seluruh tabel paket dalam satu transaksi (cepat untuk puluhan ribu baris). */
    fun writePackages(items: Collection<Pkg>, clearScans: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("packages", null, null)
            if (clearScans) db.delete("scans", null, null)
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO packages(resi,vals,base_day,attempt,scanned_at,scan_count) VALUES(?,?,?,?,?,?)",
            )
            for (p in items) {
                st.clearBindings()
                st.bindString(1, p.resi)
                st.bindString(2, p.values.joinToString(Prefs.SEP))
                p.baseDay?.let { st.bindLong(3, it) } ?: st.bindNull(3)
                p.attempt?.let { st.bindLong(4, it.toLong()) } ?: st.bindNull(4)
                p.scannedAt?.let { st.bindLong(5, it) } ?: st.bindNull(5)
                st.bindLong(6, p.scanCount.toLong())
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertScan(r: ScanRecord): Long = writableDatabase.insert(
        "scans", null,
        ContentValues().apply {
            put("resi", r.resi); put("ts", r.ts); put("found", if (r.found) 1 else 0)
            put("age", r.age); put("level", r.level.name); put("dup", if (r.dup) 1 else 0); put("source", r.source.name)
        },
    )

    fun markScanned(resi: String, ts: Long?, count: Int) {
        writableDatabase.update(
            "packages",
            ContentValues().apply { put("scanned_at", ts); put("scan_count", count) },
            "resi = ?", arrayOf(resi),
        )
    }

    fun deleteScan(resi: String, ts: Long) {
        writableDatabase.delete("scans", "resi = ? AND ts = ?", arrayOf(resi, ts.toString()))
    }

    fun clearScans() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("scans", null, null)
            db.execSQL("UPDATE packages SET scanned_at = NULL, scan_count = 0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete("packages", null, null)
        db.delete("scans", null, null)
    }
}
