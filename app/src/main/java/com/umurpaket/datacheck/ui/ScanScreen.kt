package com.umurpaket.datacheck.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.umurpaket.datacheck.AppViewModel
import com.umurpaket.datacheck.R
import com.umurpaket.datacheck.data.Code
import com.umurpaket.datacheck.data.Columns
import com.umurpaket.datacheck.data.Level
import com.umurpaket.datacheck.data.Source
import com.umurpaket.datacheck.data.ScanRecord
import com.umurpaket.datacheck.data.ScanResult
import com.umurpaket.datacheck.data.Settings
import com.umurpaket.datacheck.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun ScanScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    // Mode pratinjau/screenshot dokumentasi: tanpa kamera sungguhan
    val inspection = LocalInspectionMode.current
    var hasCam by remember {
        mutableStateOf(inspection || ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf("") }
    val v = vm.version
    val stats = remember(v, vm.settings) { vm.scanStats() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Area kamera proporsional terhadap layar (min 170dp) supaya tetap cukup untuk membidik di HP kecil
    val small = maxHeight < 560.dp
    val camHeight = (maxHeight * if (small) 0.36f else 0.34f).coerceAtLeast(170.dp)
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(camHeight).background(Color.Black)) {
            // ruang bebas di antara baris tombol (atas, ±56dp) dan baris angka (bawah, ±30dp)
            val free = (maxHeight - 96.dp).coerceAtLeast(48.dp)
            if (hasCam) {
                if (inspection) CameraMock(Modifier.fillMaxSize())
                else CameraScanner(Modifier.fillMaxSize(), torch, paused, vm::onCodes)
                val guide = if (vm.qrOnly) Modifier.size(minOf(190.dp, free, maxWidth * 0.6f))
                else Modifier.fillMaxWidth(0.82f).height(minOf(84.dp, free))
                Box(
                    Modifier.align(Alignment.Center).offset(y = 13.dp).then(guide)
                        .border(2.dp, if (vm.qrOnly) Color(0xFF80D8FF) else Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
                )
                Text(
                    if (vm.qrOnly) "MODE QR" else "Barcode / QR",
                    Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    color = if (vm.qrOnly) Color(0xFF80D8FF) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                if (paused) {
                    Text(
                        "SCAN DIJEDA", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f)).padding(10.dp),
                        color = Color.White, fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Izin kamera dibutuhkan untuk scan barcode", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Izinkan kamera") }
                }
            }
            // tombol dijejer mendatar supaya tetap muat walau area kamera pendek
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.85f))
                val torchOn = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFFFFE082))
                val qrOn = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF80D8FF))
                val manualOn = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFFC5CAE9))
                val btn = Modifier.size(42.dp)
                FilledTonalIconButton(onClick = { torch = !torch }, btn, colors = if (torch) torchOn else colors) {
                    Icon(painterResource(R.drawable.ic_torch), "Senter")
                }
                FilledTonalIconButton(onClick = { paused = !paused }, btn, colors = colors) {
                    if (paused) Icon(Icons.Default.PlayArrow, "Lanjut") else Icon(painterResource(R.drawable.ic_pause), "Jeda")
                }
                FilledTonalIconButton(onClick = { vm.qrOnly = !vm.qrOnly }, btn, colors = if (vm.qrOnly) qrOn else colors) {
                    Icon(painterResource(R.drawable.ic_qr), "Mode QR")
                }
                FilledTonalIconButton(onClick = { showManual = !showManual }, btn, colors = if (showManual) manualOn else colors) {
                    Icon(Icons.Default.Edit, "Ketik resi")
                }
            }
            StatsBar(stats, Modifier.align(Alignment.BottomStart))
        }

        ResultCard(vm.lastResult, vm.settings, vm.meta.columns, compact = small)

        if (showManual) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    manual, { manual = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Ketik No. Resi (barcode rusak)") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.checkManual(manual); manual = "" }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.checkManual(manual); manual = "" }, enabled = manual.isNotBlank()) { Text("Cek") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Riwayat scan (${fmt(vm.scans.size)})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::undoLast, enabled = vm.scans.isNotEmpty()) {
                Icon(Icons.Default.Refresh, null, Modifier.padding(end = 4.dp))
                Text("Batalkan terakhir")
            }
        }
        HorizontalDivider()
        if (vm.scans.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Belum ada scan", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(vm.scans, key = { it.ts.toString() + it.resi }) { HistoryRow(it) }
            }
        }
    }
    }
}

@Composable
private fun StatsBar(s: com.umurpaket.datacheck.ScanStats, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Discan ${fmt(s.scanned)} / ${fmt(s.total)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Kritis ${fmt(s.critScanned)}", color = Color(0xFFFF8A80), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Tidak ada ${fmt(s.notFound)}", color = Color(0xFFE1BEE7), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResultCard(r: ScanResult?, s: Settings, cols: Columns, compact: Boolean = false) {
    val level = r?.rec?.level
    val bg by animateColorAsState(
        when {
            r == null -> Color(0xFFE6E8F0)
            r.rec.dup -> DupColor
            else -> level!!.color
        },
        tween(250), label = "bg",
    )
    val fg = when {
        r == null -> Color(0xFF444444)
        r.rec.dup -> Color.White
        else -> level!!.onColor
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (r == null) {
            Text(
                "Arahkan kamera ke barcode resi.\nHijau = aman, Kuning = perhatian (≥ H+${s.warnDays}), Merah = kritis (≥ H+${s.critDays}).",
                Modifier.padding(16.dp), color = fg, fontSize = 14.sp,
            )
            return@Card
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (r.rec.dup) "SUDAH DISCAN" else r.rec.level.label,
                    color = fg, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f),
                )
                if (r.rec.found) {
                    Text(r.rec.age?.let { "H+$it" } ?: "H+?", color = fg, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(r.rec.resi, 20, FontWeight.Bold, fg)
                Text("  via ${r.rec.source.label}", color = fg, fontSize = 12.sp)
            }
            val p = r.pkg
            if (p != null) {
                // semua kolom file, dengan nama kolom sesuai judul di Excel.
                // Layar kecil: cukup 2 kolom (attempt diutamakan) supaya kamera tetap lega.
                val shown = if (!compact) cols.others else (cols.others.filter { it == cols.attempt } + cols.others.filter { it != cols.attempt && it !in cols.age }).take(2)
                for (i in shown) {
                    val v = p.values.getOrNull(i).orEmpty()
                    if (v.isBlank()) continue
                    val high = i == cols.attempt && (p.attempt ?: 0) >= s.attemptWarn
                    Text(
                        "${cols.name(i)}: $v" + if (high) "   ⚠ tinggi" else "",
                        color = fg, fontSize = 13.sp, fontWeight = if (high) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                    )
                }
                if (r.rec.dup) Text("Status umur: ${r.rec.level.label}", color = fg, fontSize = 13.sp)
            } else {
                Text("Resi ini tidak ada di file data yang diimport.", color = fg, fontSize = 13.sp)
            }
            if (r.rec.dup && r.prevAt != null) {
                Text("Pertama discan jam ${timeFmt.format(Date(r.prevAt))}", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HistoryRow(r: ScanRecord) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (r.dup) DupColor else r.level.color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Mono(r.resi, 15)
            Text(
                timeFmt.format(Date(r.ts)) + "  •  " + r.source.label + "  •  " + (if (r.dup) "scan ulang • " else "") + r.level.label.lowercase(),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            )
        }
        AgeBadge(r.age, r.level)
    }
    HorizontalDivider(Modifier.padding(start = 32.dp), color = Color(0xFFEDEEF3))
}

/** Pengganti gambar kamera untuk pratinjau/screenshot dokumentasi: label paket tiruan. */
@Composable
private fun CameraMock(modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF3A3F47))) {
        val w = size.width * 0.78f
        val h = size.height * 0.62f
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawRoundRect(Color(0xFFF3F1EA), Offset(left, top), GSize(w, h), CornerRadius(18f))
        // barcode garis
        val bx = left + w * 0.1f
        val bw = w * 0.8f
        val by = top + h * 0.4f
        val bh = h * 0.24f
        var x = bx
        var i = 0
        while (x < bx + bw) {
            val lw = if (i % 3 == 0) 7f else if (i % 2 == 0) 4f else 2.5f
            drawRect(Color(0xFF1A1A1A), Offset(x, by), GSize(lw, bh))
            x += lw + if (i % 4 == 0) 7f else 4f
            i++
        }
        // teks & QR tiruan
        drawRect(Color(0xFF1A1A1A), Offset(left + w * 0.1f, top + h * 0.12f), GSize(w * 0.35f, h * 0.05f))
        drawRect(Color(0xFF9E9E9E), Offset(left + w * 0.1f, top + h * 0.22f), GSize(w * 0.5f, h * 0.035f))
        drawRect(Color(0xFF1A1A1A), Offset(left + w * 0.7f, top + h * 0.08f), GSize(w * 0.2f, w * 0.2f))
        drawRect(Color(0xFFF3F1EA), Offset(left + w * 0.73f, top + h * 0.08f + w * 0.03f), GSize(w * 0.14f, w * 0.14f))
        drawRect(Color(0xFF1A1A1A), Offset(left + w * 0.76f, top + h * 0.08f + w * 0.06f), GSize(w * 0.08f, w * 0.08f))
        drawRect(Color(0xFF1A1A1A), Offset(left + w * 0.25f, top + h * 0.72f), GSize(w * 0.5f, h * 0.05f))
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScanner(modifier: Modifier, torch: Boolean, paused: Boolean, onCodes: (List<Code>) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
        }
    }
    val currentOnCodes by rememberUpdatedState(onCodes)
    val pausedState = rememberUpdatedState(paused)
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF, Barcode.FORMAT_EAN_13, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
            ).build(),
        )
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        var analysisRef: ImageAnalysis? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    ).build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisRef = analysis
            // Error di thread analisa tidak boleh lolos: di sebagian HP itu langsung mematikan aplikasi.
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val media = proxy.image
                    if (media == null || pausedState.value || disposed) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(img)
                        .addOnSuccessListener { list ->
                            runCatching {
                                val codes = list.mapNotNull { b ->
                                    b.rawValue?.let { Code(it, b.format == Barcode.FORMAT_QR_CODE || b.format == Barcode.FORMAT_DATA_MATRIX) }
                                }
                                if (codes.isNotEmpty() && !pausedState.value && !disposed) currentOnCodes(codes)
                            }
                        }
                        .addOnCompleteListener { runCatching { proxy.close() } }
                } catch (e: Throwable) {
                    runCatching { proxy.close() }
                }
            }
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            disposed = true
            // lepas analyzer dulu supaya kamera tidak mengirim frame ke executor yang sudah dimatikan
            runCatching { analysisRef?.clearAnalyzer() }
            runCatching { provider?.unbindAll() }
            camera = null
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }
    LaunchedEffect(camera, torch) {
        val cam = camera ?: return@LaunchedEffect
        if (cam.cameraInfo.hasFlashUnit()) runCatching { cam.cameraControl.enableTorch(torch) }
    }

    // Ketuk layar kamera untuk fokus
    DisposableEffect(previewView) {
        previewView.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                camera?.let { cam ->
                    runCatching {
                        val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                    }
                }
                v.performClick()
            }
            true
        }
        onDispose { previewView.setOnTouchListener(null) }
    }

    AndroidView({ previewView }, modifier.fillMaxHeight())
}
