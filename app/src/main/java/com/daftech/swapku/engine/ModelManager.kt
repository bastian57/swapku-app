package com.daftech.swapku.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Model download and storage for SwapKu v1.
 *
 * HARD RULE, from the research report (2026-10-30):
 * - Models are NEVER bundled in the APK.
 * - No download URL and no SHA-256 is invented here.
 *
 * What the research report actually verified:
 * - The inSwapper source page https://huggingface.co/zullum/roop/blob/main/inswapper_128.onnx
 *   is listed in the report's Sources. That is a BLOB (HTML) page, NOT a
 *   raw ONNX download URL. Downloading that page as a model would produce
 *   an HTML file, not a model, so it is stored here as sourcePageUrl only
 *   and is never used as a download URL.
 * - InsightFace's project page https://pypi.org/project/insightface/ is
 *   listed, but it contains no direct .onnx file URL for w600k_r50.onnx
 *   in the report, so no ArcFace download URL is claimed here either.
 *
 * Consequence, implemented honestly below: both v1 models have
 * downloadUrl = null and verifiedDownload = false. [ModelManager.download]
 * refuses to download them and returns a clear failure message instead
 * of guessing a URL. The app becomes usable for swapping only when the
 * user places verified model files into filesDir/models/ themselves, or
 * supplies a verified URL in the UI (downloadFromUserUrl), which the app
 * downloads without claiming that URL was verified by us.
 *
 * This is a deliberate prototype gate, not a bug: shipping a guessed
 * model URL or SHA would be worse than failing clearly.
 */
data class ModelSpec(
    val id: String,
    val fileName: String,
    val displayName: String,
    val sourcePageUrl: String,
    /** Null = no verified raw download URL. Never guess one. */
    val downloadUrl: String?,
    val verifiedDownload: Boolean,
    /** Sanity floor only (not an official size): reject tiny/corrupt files. */
    val minBytes: Long
)

sealed class ModelResult {
    data class Ready(val file: File) : ModelResult()
    data class Missing(val spec: ModelSpec, val message: String) : ModelResult()
    data class Failed(val message: String) : ModelResult()
}

class ModelManager(private val context: Context) {

    companion object {
        const val MODELS_DIR = "models"
        const val MODELS_VERSION = "v1"

        val INSWAPPER = ModelSpec(
            id = "inswapper",
            fileName = "inswapper_128_fp16.onnx",
            displayName = "inSwapper (FP16, prototipe)",
            sourcePageUrl = "https://huggingface.co/zullum/roop/blob/main/inswapper_128.onnx",
            downloadUrl = null,
            verifiedDownload = false,
            minBytes = 10_000_000L
        )

        val ARCFACE = ModelSpec(
            id = "arcface",
            fileName = "w600k_r50.onnx",
            displayName = "ArcFace ResNet-50 (embedding 512-d)",
            sourcePageUrl = "https://pypi.org/project/insightface/",
            downloadUrl = null,
            verifiedDownload = false,
            minBytes = 10_000_000L
        )

        val REQUIRED = listOf(INSWAPPER, ARCFACE)

        /**
         * SCRFD detector (det_10g.onnx) is intentionally NOT required in v1:
         * detection uses ML Kit, per the locked scope. Listing it here as
         * deferred avoids a third unverified download.
         */
        const val DETECTOR_NOTE =
            "Deteksi v1 memakai ML Kit, bukan SCRFD. File det_10g.onnx tidak diunduh di v1."
    }

    fun modelsDir(): File {
        val dir = File(File(context.filesDir, MODELS_DIR), MODELS_VERSION)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelFile(spec: ModelSpec): File = File(modelsDir(), spec.fileName)

    /** Check all required models. Returns Ready only if every file exists and passes the size floor. */
    fun checkModels(): ModelResult {
        for (spec in REQUIRED) {
            val file = modelFile(spec)
            if (!file.exists()) {
                return ModelResult.Missing(
                    spec,
                    "Model belum ada: ${spec.displayName} (${spec.fileName}). " +
                        "URL unduhan mentahnya belum terverifikasi dari laporan riset, " +
                        "jadi app tidak menebak URL. Letakkan file model yang sudah " +
                        "diverifikasi ke folder models v1 di penyimpanan app, atau " +
                        "masukkan URL terverifikasi di layar Model."
                )
            }
            if (file.length() < spec.minBytes) {
                return ModelResult.Failed(
                    "File model terlalu kecil / rusak: ${spec.fileName} " +
                        "(${file.length()} byte, minimal ${spec.minBytes}). " +
                        "Hapus file itu dan unduh ulang dari sumber terverifikasi."
                )
            }
        }
        return ModelResult.Ready(modelsDir())
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Download a model from a URL the USER supplied and confirmed.
     * The app does NOT claim this URL was verified by us. It only checks:
     * .onnx suffix, HTTP 200 / 206, resumable partial file, progress,
     * cancellation, and the size floor afterwards.
     */
    fun downloadFromUserUrl(
        spec: ModelSpec,
        userUrl: String,
        requireWifi: Boolean,
        cancelled: AtomicBoolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): ModelResult {
        if (!userUrl.lowercase().endsWith(".onnx")) {
            return ModelResult.Failed("URL model harus berakhiran .onnx: $userUrl")
        }
        if (requireWifi && !isWifiConnected()) {
            return ModelResult.Failed(
                "Hubungkan Wi-Fi dulu untuk mengunduh model (${spec.displayName}). " +
                    "Model berukuran ratusan MB; unduhan lewat data seluler dimatikan " +
                    "kecuali lo konfirmasi lanjut di dialog Wi-Fi."
            )
        }
        val dir = modelsDir()
        val finalFile = File(dir, spec.fileName)
        val partFile = File(dir, spec.fileName + ".part")
        return try {
            var downloaded = if (partFile.exists()) partFile.length() else 0L
            val connection = URL(userUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            if (downloaded > 0L) {
                connection.setRequestProperty("Range", "bytes=$downloaded-")
            }
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return ModelResult.Failed("Unduhan gagal, HTTP $code untuk $userUrl")
            }
            val contentLength = connection.contentLengthLong
            val total = if (contentLength > 0L) downloaded + contentLength else -1L
            if (code == HttpURLConnection.HTTP_OK) {
                // Server ignored Range: restart from zero, do not append.
                downloaded = 0L
                if (partFile.exists()) partFile.delete()
            }
            connection.inputStream.use { input ->
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(downloaded)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) {
                            return ModelResult.Failed("Unduhan dibatalkan. File parsial tersimpan dan bisa dilanjutkan.")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (partFile.length() < spec.minBytes) {
                partFile.delete()
                return ModelResult.Failed(
                    "File hasil unduhan terlalu kecil (${partFile.length()} byte). " +
                        "Kemungkinan URL itu bukan file ONNX, atau unduhan terpotong."
                )
            }
            // No SHA is checked here on purpose: no SHA was verified in the
            // research report, and inventing one would be worse than none.
            // The size floor + .onnx check + ONNX session load (in the
            // engine) are the v1 checks. A verified SHA is a v1.1 task
            // once a model URL is verified.
            if (finalFile.exists()) finalFile.delete()
            partFile.renameTo(finalFile)
            FileOutputStream(finalFile, true).close()
            ModelResult.Ready(finalFile)
        } catch (e: Exception) {
            ModelResult.Failed("Unduhan gagal: ${e.message ?: e.javaClass.simpleName}. File parsial tersimpan dan bisa dilanjutkan.")
        }
    }

    /**
     * The default download path. It always fails clearly for v1 models
     * because no verified raw URL exists in the research report.
     * This method exists so the UI has one honest entry point and so
     * no caller can accidentally invent a URL elsewhere.
     */
    fun downloadDefault(
        spec: ModelSpec,
        @Suppress("UNUSED_PARAMETER") cancelled: AtomicBoolean
    ): ModelResult {
        if (!spec.verifiedDownload || spec.downloadUrl == null) {
            return ModelResult.Missing(
                spec,
                "URL unduhan untuk ${spec.displayName} belum terverifikasi. " +
                    "Sumber yang terverifikasi di laporan riset hanya halaman sumber: " +
                    spec.sourcePageUrl + ". " +
                    "Jangan unduh halaman HTML itu sebagai model. " +
                    "Masukkan URL file .onnx yang sudah diverifikasi di layar Model, " +
                    "atau letakkan file ${spec.fileName} yang sudah diverifikasi " +
                    "ke penyimpanan app."
            )
        }
        return ModelResult.Failed("Jalur unduhan default tidak terpakai di v1.")
    }
}
