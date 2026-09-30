package com.umurpaket.datacheck

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menyimpan detail crash ke file, lalu ditampilkan saat aplikasi dibuka lagi. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = packageInfo(ctx)
                file(ctx).writeText(
                    "Waktu: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())}\n" +
                        "Versi: $info • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Laporan crash terakhir (null bila tidak ada). Tidak dihapus sampai [clear], supaya tidak hilang bila crash berulang. */
    fun peek(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    private fun packageInfo(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
