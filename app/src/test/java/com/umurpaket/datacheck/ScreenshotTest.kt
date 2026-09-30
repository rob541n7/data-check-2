package com.umurpaket.datacheck

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.umurpaket.datacheck.data.Aging
import com.umurpaket.datacheck.data.Columns
import com.umurpaket.datacheck.data.Importer
import com.umurpaket.datacheck.data.Level
import com.umurpaket.datacheck.data.Meta
import com.umurpaket.datacheck.data.Pkg
import com.umurpaket.datacheck.data.ScanRecord
import com.umurpaket.datacheck.data.ScanResult
import com.umurpaket.datacheck.data.Settings
import com.umurpaket.datacheck.data.Source
import com.umurpaket.datacheck.ui.AppTheme
import com.umurpaket.datacheck.ui.ImportDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate
import kotlin.random.Random

/**
 * Membuat screenshot tiap menu untuk README (docs/screenshots/) dengan data contoh berisi resi palsu.
 * Jalankan: ./gradlew testDebugUnitTest --tests "*ScreenshotTest*"
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = "../docs/screenshots"
    private val today = LocalDate.now().toEpochDay()
    private val now = System.currentTimeMillis()
    private val cols = Columns(listOf("No. Waybill", "UMUR PAKET", "DELIVERY ATTEMPT", "Jenis Scan", "Status Longtail"), 0, listOf(1), 2)
    private val aging = Aging(Settings(), today)

    private val items: List<Pkg> = run {
        val rnd = Random(7)
        val jenis = listOf("Scan Paket Bermasalah", "Scan Delivery", "Pack", "Scan Sampai")
        val status = listOf("Longtail", "Longtail", "Normal", "Retur")
        val ages = listOf(0, 1, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 16, 19, 23)
        List(64) { i ->
            val age = ages[rnd.nextInt(ages.size)]
            val att = rnd.nextInt(0, 7)
            val resi = "JX10000%05d".format(i + 1)
            val scanned = rnd.nextInt(10) < 4
            Pkg(
                resi, listOf(resi, "$age", "$att", jenis[rnd.nextInt(jenis.size)], status[rnd.nextInt(status.size)]),
                today - age, att, if (scanned) now - rnd.nextLong(60_000, 3_600_000) else null, if (scanned) 1 else 0,
            )
        }
    }

    private fun rec(p: Pkg?, resi: String, minutesAgo: Int, dup: Boolean = false, source: Source = Source.BARCODE): ScanRecord {
        val age = p?.let(aging::age)
        return ScanRecord(0, resi, now - minutesAgo * 60_000L, p != null, age, if (p == null) Level.NOT_FOUND else aging.level(age), dup, source)
    }

    private fun history(): List<ScanRecord> {
        val scanned = items.filter { it.scanCount > 0 }.take(9)
        return listOf(rec(scanned[0], scanned[0].resi, 0)) +
            listOf(rec(null, "JX1999999999", 1)) +
            listOf(rec(scanned[1], scanned[1].resi, 2, dup = true)) +
            scanned.drop(2).mapIndexed { i, p -> rec(p, p.resi, 3 + i, source = if (i == 2) Source.QR else Source.BARCODE) }
    }

    private fun vm(result: ScanResult? = null, pending: PendingImport? = null): AppViewModel {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val deadline = System.currentTimeMillis() + 15_000
        while (!vm.loaded && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        vm.seedDemo(Meta("File Scan Perdagangan 1 Longtail 30 Sept.xlsx", now - 3_600_000, cols), items, history(), result, pending)
        return vm
    }

    private fun shoot(name: String, vm: AppViewModel, tab: Int) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    AppRoot(vm, startTab = tab)
                    ImportDialog(vm)
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("$dir/$name.png")
    }

    private fun resultFor(level: Level, source: Source = Source.BARCODE): ScanResult {
        val p = items.firstOrNull { aging.level(it) == level && (it.attempt ?: 0) >= 3 && it.scanCount > 0 }
            ?: items.first { aging.level(it) == level }
        return ScanResult(rec(p, p.resi, 0, source = source), p, null)
    }

    @Test
    fun scanKritis() = shoot("01-scan-kritis", vm(resultFor(Level.CRIT)), 0)

    @Test
    fun scanQr() {
        val vm = vm(resultFor(Level.OK, Source.QR))
        vm.qrOnly = true
        shoot("02-scan-mode-qr", vm, 0)
    }

    /** Pemeriksaan tata letak di HP layar kecil (tidak dipakai di README). */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun scanSmallPhone() {
        val vm = vm(resultFor(Level.CRIT))
        vm.qrOnly = true
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { AppRoot(vm, startTab = 0) } }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshot-check/scan-hp-kecil.png")
    }

    @Test
    fun data() = shoot("03-data", vm(), 1)

    @Test
    fun importDialog() {
        val rows = listOf(cols.names) + items.take(6).map { it.values }
        shoot("04-import-pilih-kolom", vm(pending = PendingImport("File Scan Raya Longtail 30 Sept.xlsx", rows, Importer.guess(rows))), 1)
    }

    @Test
    @Config(qualifiers = "w393dp-h2500dp-xhdpi")
    fun ringkasan() = shoot("05-ringkasan", vm(), 2)

    @Test
    fun pengaturan() = shoot("06-pengaturan", vm(), 3)

    @Test
    fun laporanCrash() {
        compose.setContent {
            AppTheme {
                CrashScreen(
                    "Waktu: 30/09/2026 08:15:02\nVersi: 2.2.3 • Android 16 (API 36) • Contoh HP\nThread: main\n\n" +
                        "java.lang.IllegalStateException: contoh laporan crash\n\tat com.umurpaket.datacheck...\n",
                ) {}
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("$dir/07-laporan-crash.png")
    }
}
