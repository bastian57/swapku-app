package com.daftech.swapku.ui

import android.graphics.BitmapFactory
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.daftech.swapku.AppViewModel
import com.daftech.swapku.engine.CameraHelper
import java.io.File
import kotlinx.coroutines.delay

private val TextWhite = Color.White
private val TextSecondary = Color(0xFFCCCCCC)
private val TextWarning = Color(0xFFFFC107)
private val TextError = Color(0xFFFF6B6B)
private val BgDark = Color(0xFF121212)
private val SurfaceDark = Color(0xFF1E1E1E)

/**
 * SwapKu v1 main screen. Dark theme, every Text sets its colour
 * explicitly (see Theme.kt for why).
 *
 * Honest UI rules enforced here:
 * - The camera preview is labelled "Preview kamera ASLI (belum di-swap)".
 *   It is never presented as a swapped preview.
 * - The Swap button is disabled until: consent is checked, both photos
 *   are chosen, and models are ready.
 * - Video is recorded normally first, then processed offline. The
 *   recorded file is never labelled as swapped until processing
 *   actually succeeds.
 * - Every result is labelled AI-modified in the UI and in its filename.
 */
@Composable
fun MainScreen(
    viewModel: AppViewModel,
    onPickSourcePhoto: () -> Unit,
    onPickTargetPhoto: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraHelper = remember { CameraHelper(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingMessage by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { cameraHelper.unbind() }
    }

    // Auto-stop video at 15 seconds (v1 cap).
    LaunchedEffect(isRecording) {
        if (isRecording) {
            delay(15_000L)
            if (isRecording) {
                cameraHelper.stopVideoRecording()
                isRecording = false
                recordingMessage = "Rekaman berhenti otomatis di 15 detik (batas v1)."
            }
        }
    }

    if (viewModel.showAbout) {
        AlertDialog(
            onDismissRequest = { viewModel.updateAboutVisible(false) },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = "Tentang SwapKu v1.0.0",
                    color = TextWhite,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "SwapKu adalah app kamera sendiri untuk konten. " +
                        "Semua proses on-device, tanpa upload, tanpa suntik kamera " +
                        "ke TikTok/WhatsApp, tanpa live impersonation.\n\n" +
                        "PERINGATAN LISENSI: model inSwapper/ArcFace dari InsightFace " +
                        "hanya untuk riset non-komersial. APK ini prototipe. " +
                        "Jangan dijual atau didistribusikan secara komersial sebelum " +
                        "lisensi Ghost/HyperSwap terverifikasi atau lisensi komersial " +
                        "InsightFace diperoleh.\n\n" +
                        "Hasil swap dilabeli AI-modified. ML Kit hanya untuk deteksi; " +
                        "embedding berasal dari model ArcFace ONNX, bukan dari ML Kit.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.updateAboutVisible(false) }) {
                    Text(text = "Tutup", color = TextWhite, fontSize = 14.sp)
                }
            }
        )
    }

    if (viewModel.showWifiDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.updateWifiDialogVisible(false) },
            containerColor = SurfaceDark,
            title = {
                Text(text = "Unduhan model butuh Wi-Fi", color = TextWhite, fontSize = 18.sp)
            },
            text = {
                Text(
                    text = "Model berukuran ratusan MB. Lanjut pakai data seluler bisa mahal. " +
                        "Pilih Lanjut hanya kalau lo sadar kuotanya.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateWifiConfirmed(true)
                    viewModel.updateWifiDialogVisible(false)
                    viewModel.downloadModels()
                }) {
                    Text(text = "Lanjut pakai data", color = TextWhite, fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.updateWifiDialogVisible(false) }) {
                    Text(text = "Batal", color = TextWhite, fontSize = 14.sp)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "SwapKu", color = TextWhite, fontSize = 28.sp)
        Text(
            text = "Swap wajah dari foto, di app sendiri, tanpa upload.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Text(
            text = "PROTOTIPE — LISENSI BELUM CLEAR UNTUK DISTRIBUSI KOMERSIAL. " +
                "Model inSwapper/ArcFace hanya untuk riset non-komersial. " +
                "Jangan jual APK ini sebelum lisensi Ghost/HyperSwap terverifikasi " +
                "atau lisensi komersial InsightFace diperoleh.",
            color = TextWarning,
            fontSize = 13.sp
        )
        OutlinedButton(onClick = { viewModel.updateAboutVisible(true) }) {
            Text(text = "Tentang & Lisensi", color = TextWhite, fontSize = 14.sp)
        }

        // --- Models ---
        Text(text = "1. Model (unduh di app, bukan dibundel)", color = TextWhite, fontSize = 16.sp)
        Text(
            text = if (viewModel.modelsReady) {
                "Status model: SIAP."
            } else {
                "Status model: BELUM SIAP. Lihat pesan status di bawah."
            },
            color = TextSecondary,
            fontSize = 14.sp
        )
        TextField(
            value = viewModel.inswapperUrlInput,
            onValueChange = { viewModel.updateInswapperUrl(it) },
            label = { Text(text = "URL file inSwapper .onnx (terverifikasi, opsional)", color = TextSecondary, fontSize = 12.sp) },
            placeholder = { Text(text = "https://…/inswapper_128_fp16.onnx", color = TextSecondary, fontSize = 12.sp) },
            colors = TextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark
            ),
            modifier = Modifier.fillMaxWidth()
        )
        TextField(
            value = viewModel.arcfaceUrlInput,
            onValueChange = { viewModel.updateArcfaceUrl(it) },
            label = { Text(text = "URL file ArcFace .onnx (terverifikasi, opsional)", color = TextSecondary, fontSize = 12.sp) },
            placeholder = { Text(text = "https://…/w600k_r50.onnx", color = TextSecondary, fontSize = 12.sp) },
            colors = TextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Catatan jujur: tidak ada URL unduhan mentah yang terverifikasi " +
                "dari laporan riset untuk kedua model ini, jadi app tidak menebak URL. " +
                "Kalau kolom di atas kosong, tombol unduh akan gagal dengan pesan jelas. " +
                "Isi kolom itu hanya dengan URL file .onnx yang sudah lo verifikasi sendiri, " +
                "atau letakkan file model yang sudah diverifikasi ke penyimpanan app.",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.downloadModels() },
                enabled = !viewModel.isProcessing
            ) {
                Text(text = "Unduh / Cek Model", color = TextWhite, fontSize = 14.sp)
            }
            OutlinedButton(onClick = { viewModel.refreshModelStatus() }) {
                Text(text = "Cek Ulang", color = TextWhite, fontSize = 14.sp)
            }
        }

        // --- Camera preview (REAL, not swapped) ---
        Text(text = "2. Kamera (preview ASLI, belum di-swap)", color = TextWhite, fontSize = 16.sp)
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    cameraHelper.bindPreview(lifecycleOwner, previewView) { error ->
                        viewModel.updateStatus(error)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        )
        Text(
            text = "Preview di atas adalah kamera asli. Swap TIDAK terjadi di preview " +
                "di v1. Foto/video yang lo ambil akan di-swap offline setelahnya.",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                cameraHelper.capturePhoto { file, error ->
                    if (file != null) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            viewModel.selectTargetBitmap(bitmap)
                            viewModel.updateStatus("Foto target dari kamera siap.")
                        } else {
                            viewModel.updateStatus("Foto berhasil diambil tapi gagal dibaca.")
                        }
                    } else {
                        viewModel.updateStatus(error ?: "Foto gagal.")
                    }
                }
            }) {
                Text(text = "Ambil Foto Target", color = TextWhite, fontSize = 14.sp)
            }
            Button(onClick = {
                if (!isRecording) {
                    cameraHelper.startVideoRecording(
                        onEvent = { event ->
                            if (event is VideoRecordEvent.Finalize) {
                                isRecording = false
                                if (!event.hasError()) {
                                    val path = event.outputResults.outputUri.path
                                    if (path != null) {
                                        viewModel.selectRecordedVideo(File(path))
                                        recordingMessage = "Video direkam (belum di-swap). Lanjut ke proses offline di bawah."
                                    }
                                } else {
                                    recordingMessage = "Rekaman gagal: ${event.error}"
                                }
                            }
                        },
                        onError = { error -> viewModel.updateStatus(error) }
                    )
                    isRecording = true
                    recordingMessage = "Merekam… (otomatis berhenti di 15 detik)"
                } else {
                    cameraHelper.stopVideoRecording()
                    isRecording = false
                }
            }) {
                Text(
                    text = if (isRecording) "Berhenti Rekam" else "Rekam Video (maks 15 dtk)",
                    color = TextWhite,
                    fontSize = 14.sp
                )
            }
        }
        if (recordingMessage.isNotEmpty()) {
            Text(text = recordingMessage, color = TextSecondary, fontSize = 13.sp)
        }
        if (viewModel.recordedVideoFile != null) {
            Text(
                text = "Video terekam: ${viewModel.recordedVideoFile?.name}",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // --- Photos ---
        Text(text = "3. Foto identitas & target", color = TextWhite, fontSize = 16.sp)
        Button(onClick = onPickSourcePhoto) {
            Text(text = "Pilih Foto Identitas (sumber wajah)", color = TextWhite, fontSize = 14.sp)
        }
        if (viewModel.sourceBitmap != null) {
            Image(
                bitmap = viewModel.sourceBitmap!!.asImageBitmap(),
                contentDescription = "Foto identitas",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
        }
        Button(onClick = onPickTargetPhoto) {
            Text(text = "Pilih Foto Target dari Galeri", color = TextWhite, fontSize = 14.sp)
        }
        if (viewModel.targetBitmap != null) {
            Image(
                bitmap = viewModel.targetBitmap!!.asImageBitmap(),
                contentDescription = "Foto target",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
        }

        // --- Consent gate ---
        Text(text = "4. Persetujuan (wajib sebelum swap)", color = TextWhite, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = viewModel.consentConfirmed,
                onCheckedChange = { viewModel.updateConsent(it) }
            )
            Text(
                text = "Foto ini milik gue sendiri, atau gue sudah punya izin " +
                    "dari pemilik wajahnya untuk dipakai di konten ini. " +
                    "Gue tidak akan memakai app ini untuk menyamar jadi orang lain " +
                    "di live/video call.",
                color = TextWhite,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
        }

        // --- Swap photo ---
        Button(
            onClick = { viewModel.runPhotoSwap() },
            enabled = !viewModel.isProcessing && viewModel.consentConfirmed &&
                viewModel.sourceBitmap != null && viewModel.targetBitmap != null &&
                viewModel.modelsReady
        ) {
            Text(text = "Swap Foto", color = TextWhite, fontSize = 16.sp)
        }
        if (viewModel.isProcessing) {
            LinearProgressIndicator(
                progress = { viewModel.progressFraction },
                modifier = Modifier.fillMaxWidth()
            )
            Text(text = viewModel.progressText, color = TextSecondary, fontSize = 13.sp)
            OutlinedButton(onClick = { viewModel.cancelProcessing() }) {
                Text(text = "Batalkan", color = TextWhite, fontSize = 14.sp)
            }
        }
        if (viewModel.resultBitmap != null) {
            Text(text = "Hasil (AI-modified):", color = TextWhite, fontSize = 16.sp)
            Image(
                bitmap = viewModel.resultBitmap!!.asImageBitmap(),
                contentDescription = "Hasil swap, AI-modified",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            )
            Button(onClick = { viewModel.saveResultPhoto() }) {
                Text(text = "Simpan Hasil ke Download/SwapKu", color = TextWhite, fontSize = 14.sp)
            }
        }

        // --- Video offline ---
        Text(text = "5. Video offline (rekam dulu, proses kemudian)", color = TextWhite, fontSize = 16.sp)
        Text(
            text = "Batas v1: maks 15 detik, maks 720p, satu wajah. " +
                "Proses bisa memakan waktu menit di HP kelas G95. " +
                "Kalau gagal, video asli tetap tersimpan dan file parsial dihapus.",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Button(
            onClick = { viewModel.runVideoProcessing() },
            enabled = !viewModel.isProcessing && viewModel.consentConfirmed &&
                viewModel.recordedVideoFile != null && viewModel.sourceBitmap != null &&
                viewModel.modelsReady
        ) {
            Text(text = "Proses Video Offline", color = TextWhite, fontSize = 14.sp)
        }
        if (viewModel.processedVideoFile != null) {
            Text(
                text = "Video hasil (AI-modified): ${viewModel.processedVideoFile?.name}",
                color = TextWhite,
                fontSize = 14.sp
            )
            Button(onClick = { viewModel.saveResultVideo() }) {
                Text(text = "Simpan Video Hasil ke Download/SwapKu", color = TextWhite, fontSize = 14.sp)
            }
        }

        // --- Status ---
        if (viewModel.statusMessage.isNotEmpty()) {
            Text(text = viewModel.statusMessage, color = TextSecondary, fontSize = 13.sp)
        }
        if (viewModel.savedResultUri != null) {
            Text(text = "Tersimpan: ${viewModel.savedResultUri}", color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SwapKu v1.0.0 — on-device only. Tidak ada upload, tidak ada " +
                "virtual camera, tidak ada live impersonation.",
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}
