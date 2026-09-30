package com.umurpaket.datacheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umurpaket.datacheck.ui.AppTheme
import com.umurpaket.datacheck.ui.DataScreen
import com.umurpaket.datacheck.ui.ImportDialog
import com.umurpaket.datacheck.ui.ScanScreen
import com.umurpaket.datacheck.ui.SettingsScreen
import com.umurpaket.datacheck.ui.SummaryScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        val crash = CrashLog.peek(this)
        setContent {
            AppTheme {
                var pending by remember { mutableStateOf(crash) }
                val report = pending
                // Tampilkan laporan crash dulu (kamera belum menyala), lalu buka tab Data supaya aman.
                if (report != null) CrashScreen(report) { CrashLog.clear(this); pending = null }
                else AppRoot(vm, startTab = if (crash != null) 1 else 0)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** File Excel yang dibuka/dibagikan dari aplikasi lain (WhatsApp, File Manager). */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) vm.requestImport(uri)
    }
}

/** Layar laporan crash: bisa dibagikan (WhatsApp dll) atau disalin, sebelum aplikasi dilanjutkan. */
@Composable
internal fun CrashScreen(text: String, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
        Text("Aplikasi sempat berhenti", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Tekan Bagikan lalu kirim laporan ini (mis. lewat WhatsApp) supaya penyebabnya bisa diperbaiki.",
            fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            text,
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF1F2F6)).padding(8.dp)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Laporan crash Data Check 2")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { ctx.startActivity(Intent.createChooser(send, "Bagikan laporan")) }
            }, Modifier.weight(1f)) { Text("Bagikan") }
            OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("crash", text))
            }, Modifier.weight(1f)) { Text("Salin") }
        }
        TextButton(onClick = onContinue, Modifier.fillMaxWidth()) { Text("Lanjutkan ke aplikasi") }
    }
}

private data class Tab(val title: String, val label: String)

private val tabs = listOf(
    Tab("Scan Umur Paket", "Scan"),
    Tab("Data Paket", "Data"),
    Tab("Ringkasan", "Ringkasan"),
    Tab("Pengaturan", "Atur"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppRoot(vm: AppViewModel, startTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            vm.consumeMessage()
            snack.showSnackbar(it)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::requestImport)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        uri?.let(vm::export)
    }
    val onImport = { importLauncher.launch(arrayOf("*/*")) }
    val onExport = { exportLauncher.launch(vm.suggestedExportName()) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(tabs[tab].title, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1F3A93), titleContentColor = Color.White),
                )
                vm.busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 12.sp)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, label = { Text(t.label) },
                        icon = {
                            when (i) {
                                0 -> Icon(painterResource(R.drawable.ic_scan), null)
                                1 -> Icon(Icons.AutoMirrored.Filled.List, null)
                                2 -> Icon(painterResource(R.drawable.ic_chart), null)
                                else -> Icon(Icons.Default.Settings, null)
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> ScanScreen(vm)
                1 -> DataScreen(vm, onImport, onExport)
                2 -> SummaryScreen(vm, onExport)
                else -> SettingsScreen(vm)
            }
        }
    }
    ImportDialog(vm)
}

