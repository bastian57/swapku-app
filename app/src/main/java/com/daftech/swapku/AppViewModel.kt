package com.daftech.swapku

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daftech.swapku.engine.FaceSwapEngine
import com.daftech.swapku.engine.ModelManager
import com.daftech.swapku.engine.ModelResult
import com.daftech.swapku.engine.StorageHelper
import com.daftech.swapku.engine.VideoProcessor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SwapKu v1 ViewModel.
 *
 * Naming rule enforced here (the Compose pitfall from earlier projects):
 * NO function is named set<Property> where a Compose var shares that
 * name. State is changed through selectX / updateX functions only.
 * Example: the var is consentConfirmed, the function is updateConsent,
 * never setConsentConfirmed. The var is sourceBitmap, the function is
 * selectSourceBitmap, never setSourceBitmap.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val modelManager = ModelManager(application)
    private val engine = FaceSwapEngine(application)
    private val videoProcessor = VideoProcessor(application, engine)
    private val cancelled = AtomicBoolean(false)

    var sourceBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var targetBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var resultBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var isProcessing by mutableStateOf(false)
        private set
    var progressFraction by mutableStateOf(0f)
        private set
    var progressText by mutableStateOf("")
        private set
    var statusMessage by mutableStateOf("")
        private set
    var modelsReady by mutableStateOf(false)
        private set
    var consentConfirmed by mutableStateOf(false)
        private set
    var showAbout by mutableStateOf(false)
        private set
    var showWifiDialog by mutableStateOf(false)
        private set
    var wifiConfirmed by mutableStateOf(false)
        private set
    var recordedVideoFile by mutableStateOf<File?>(null)
        private set
    var processedVideoFile by mutableStateOf<File?>(null)
        private set
    var inswapperUrlInput by mutableStateOf("")
        private set
    var arcfaceUrlInput by mutableStateOf("")
        private set
    var savedResultUri by mutableStateOf<String?>(null)
        private set

    private var cachedEmbedding: FloatArray? = null
    private var cachedEmbeddingFor: Bitmap? = null

    // --- selectX / updateX state changes (never setX) ---

    fun selectSourceBitmap(bitmap: Bitmap?) {
        sourceBitmap = bitmap
        if (bitmap !== cachedEmbeddingFor) {
            cachedEmbedding = null
            cachedEmbeddingFor = null
        }
        resultBitmap = null
        savedResultUri = null
    }

    fun selectTargetBitmap(bitmap: Bitmap?) {
        targetBitmap = bitmap
        resultBitmap = null
        savedResultUri = null
    }

    fun selectRecordedVideo(file: File?) {
        recordedVideoFile = file
        processedVideoFile = null
    }

    fun updateConsent(confirmed: Boolean) {
        consentConfirmed = confirmed
    }

    fun updateAboutVisible(visible: Boolean) {
        showAbout = visible
    }

    fun updateWifiDialogVisible(visible: Boolean) {
        showWifiDialog = visible
    }

    fun updateWifiConfirmed(confirmed: Boolean) {
        wifiConfirmed = confirmed
    }

    fun updateInswapperUrl(text: String) {
        inswapperUrlInput = text
    }

    fun updateArcfaceUrl(text: String) {
        arcfaceUrlInput = text
    }

    fun updateStatus(text: String) {
        statusMessage = text
    }

    fun clearResult() {
        resultBitmap = null
        savedResultUri = null
    }

    fun cancelProcessing() {
        cancelled.set(true)
        progressText = "Membatalkan…"
    }

    // --- models ---

    fun refreshModelStatus() {
        when (val result = modelManager.checkModels()) {
            is ModelResult.Ready -> {
                modelsReady = true
                statusMessage = "Model siap. " + ModelManager.DETECTOR_NOTE
            }
            is ModelResult.Missing -> {
                modelsReady = false
                statusMessage = result.message
            }
            is ModelResult.Failed -> {
                modelsReady = false
                statusMessage = result.message
            }
        }
    }

    fun downloadModels() {
        if (isProcessing) return
        viewModelScope.launch(Dispatchers.IO) {
            cancelled.set(false)
            withContext(Dispatchers.Main) {
                isProcessing = true
                progressText = "Memeriksa model…"
            }
            for (spec in ModelManager.REQUIRED) {
                val file = modelManager.modelFile(spec)
                if (file.exists() && file.length() >= spec.minBytes) continue
                val userUrl = if (spec.id == "inswapper") inswapperUrlInput.trim() else arcfaceUrlInput.trim()
                if (userUrl.isBlank()) {
                    val missing = modelManager.downloadDefault(spec, cancelled)
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        statusMessage = when (missing) {
                            is ModelResult.Missing -> missing.message
                            is ModelResult.Failed -> missing.message
                            else -> "Model ${spec.displayName} belum ada."
                        }
                    }
                    return@launch
                }
                if (!wifiConfirmed && !modelManager.isWifiConnected()) {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        showWifiDialog = true
                        statusMessage = "Unduhan model butuh Wi-Fi. Konfirmasi di dialog untuk lanjut pakai data seluler, atau hubungkan Wi-Fi dulu."
                    }
                    return@launch
                }
                val result = modelManager.downloadFromUserUrl(
                    spec = spec,
                    userUrl = userUrl,
                    requireWifi = !wifiConfirmed,
                    cancelled = cancelled
                ) { downloaded, total ->
                    viewModelScope.launch(Dispatchers.Main) {
                        progressText = if (total > 0) {
                            "Mengunduh ${spec.displayName}: ${downloaded / 1_000_000} / ${total / 1_000_000} MB"
                        } else {
                            "Mengunduh ${spec.displayName}: ${downloaded / 1_000_000} MB"
                        }
                        progressFraction = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                    }
                }
                if (result is ModelResult.Failed) {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        statusMessage = result.message
                    }
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                isProcessing = false
                progressFraction = 1f
                progressText = ""
                refreshModelStatus()
            }
        }
    }

    // --- photo swap ---

    fun runPhotoSwap() {
        if (isProcessing) return
        val source = sourceBitmap
        val target = targetBitmap
        if (!consentConfirmed) {
            statusMessage = "Centang persetujuan dulu: foto ini milik lo sendiri atau lo sudah punya izin untuk memakainya."
            return
        }
        if (source == null || target == null) {
            statusMessage = "Pilih foto identitas DAN foto target dulu."
            return
        }
        if (!modelsReady) {
            refreshModelStatus()
            if (!modelsReady) return
        }
        viewModelScope.launch(Dispatchers.Default) {
            cancelled.set(false)
            withContext(Dispatchers.Main) {
                isProcessing = true
                progressFraction = 0f
                progressText = "Menghitung embedding foto identitas…"
                statusMessage = ""
            }
            try {
                val embedding = if (cachedEmbedding != null && cachedEmbeddingFor === source) {
                    cachedEmbedding!!
                } else {
                    engine.computeSourceEmbedding(source).also {
                        cachedEmbedding = it
                        cachedEmbeddingFor = source
                    }
                }
                withContext(Dispatchers.Main) {
                    progressFraction = 0.4f
                    progressText = "Menukar wajah di foto target…"
                }
                val swapped = engine.swapPhoto(target, embedding)
                withContext(Dispatchers.Main) {
                    resultBitmap = swapped
                    isProcessing = false
                    progressFraction = 1f
                    progressText = ""
                    statusMessage = "Selesai. Hasil ini AI-modified dan disimpan terpisah — foto asli tidak ditimpa."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    progressFraction = 0f
                    progressText = ""
                    statusMessage = "Swap gagal: ${e.message ?: e.javaClass.simpleName}. Foto asli tetap aman."
                }
            }
        }
    }

    fun saveResultPhoto() {
        val result = resultBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val uri = StorageHelper.saveSwappedPhoto(
                getApplication(),
                result,
                "swapku_" + System.currentTimeMillis()
            )
            withContext(Dispatchers.Main) {
                savedResultUri = uri?.toString()
                statusMessage = if (uri != null) {
                    "Hasil tersimpan di Download/SwapKu (AI-modified). URI: $uri"
                } else {
                    "Gagal menyimpan hasil. Coba lagi."
                }
            }
        }
    }

    // --- video (offline, record-then-process) ---

    fun runVideoProcessing() {
        if (isProcessing) return
        val input = recordedVideoFile
        val source = sourceBitmap
        if (!consentConfirmed) {
            statusMessage = "Centang persetujuan dulu sebelum memproses video."
            return
        }
        if (input == null || source == null) {
            statusMessage = "Rekam video dulu (maks 15 detik, 720p) dan pilih foto identitas dulu."
            return
        }
        if (!modelsReady) {
            refreshModelStatus()
            if (!modelsReady) return
        }
        viewModelScope.launch(Dispatchers.Default) {
            cancelled.set(false)
            withContext(Dispatchers.Main) {
                isProcessing = true
                progressFraction = 0f
                progressText = "Menghitung embedding foto identitas…"
            }
            try {
                val embedding = if (cachedEmbedding != null && cachedEmbeddingFor === source) {
                    cachedEmbedding!!
                } else {
                    engine.computeSourceEmbedding(source).also {
                        cachedEmbedding = it
                        cachedEmbeddingFor = source
                    }
                }
                val output = File(
                    getApplication<Application>().cacheDir,
                    "swapku_swapped_" + System.currentTimeMillis() + ".mp4"
                )
                val result = videoProcessor.processVideo(
                    input = input,
                    output = output,
                    sourceEmbedding = embedding,
                    cancelled = cancelled
                ) { done, total, message ->
                    viewModelScope.launch(Dispatchers.Main) {
                        progressText = message
                        progressFraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
                    }
                }
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    progressText = ""
                    when (result) {
                        is VideoProcessor.ProcessResult.Success -> {
                            processedVideoFile = result.file
                            statusMessage = "Video selesai diproses. Video asli tetap tersimpan terpisah. Simpan hasil dari tombol di bawah."
                        }
                        is VideoProcessor.ProcessResult.VideoOnly -> {
                            processedVideoFile = result.file
                            statusMessage = result.warning + " Video asli tetap ada."
                        }
                        is VideoProcessor.ProcessResult.Cancelled -> {
                            statusMessage = "Proses video dibatalkan. File parsial dihapus. Video asli tetap ada."
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    progressFraction = 0f
                    progressText = ""
                    statusMessage = "Proses video gagal: ${e.message ?: e.javaClass.simpleName}. Video asli tetap tersimpan."
                }
            }
        }
    }

    fun saveResultVideo() {
        val file = processedVideoFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val uri = StorageHelper.saveSwappedVideo(
                getApplication(),
                file,
                "swapku_video_" + System.currentTimeMillis()
            )
            withContext(Dispatchers.Main) {
                savedResultUri = uri?.toString()
                statusMessage = if (uri != null) {
                    "Video hasil tersimpan di Download/SwapKu (AI-modified). URI: $uri"
                } else {
                    "Gagal menyimpan video hasil. Coba lagi."
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { engine.close() } catch (_: Exception) { }
    }
}
