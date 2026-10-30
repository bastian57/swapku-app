package com.daftech.swapku.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One-shot face swap engine for SwapKu v1.
 *
 * Direction, fixed: a still PHOTO supplies identity (embedding computed
 * once and cached), a camera/target photo or video frame RECEIVES it.
 * The reverse direction would need the embedding recomputed per frame
 * and is not in v1.
 *
 * Steps, matching the research report pipeline:
 * 1. ML Kit detects faces (detection only, never embeddings).
 * 2. Map ML Kit landmarks to the 5-point ArcFace template.
 * 3. Align the source photo face to 112x112, run ArcFace ONNX once,
 *    L2-normalise to a 512-d vector, cache it.
 * 4. Align the target face to 128x128, run inSwapper ONNX with the
 *    cached embedding, get a swapped 128x128 crop.
 * 5. Per-channel colour match (Reinhard-style) against the target crop.
 * 6. Inverse warp + feathered elliptical blend back into the full photo.
 *
 * ONNX input/output handling is DYNAMIC, not name-guessed: the code
 * reads session input/output names and shapes from session metadata,
 * then classifies inputs by shape (image [1,3,128,128] or [1,3,112,112]
 * vs embedding [1,512]). If a model exposes different names or shapes,
 * the run fails with a clear message naming the actual shapes, instead
 * of crashing or silently producing a black frame.
 *
 * Threading: every ONNX call must run on a single background thread
 * owned by the ViewModel. Never call this from the main thread.
 * Sessions are created lazily and reused; close() releases them.
 *
 * LICENCE: inSwapper/ArcFace weights are InsightFace non-commercial
 * research only (see README + Tentang). This engine is a prototype
 * engine. It does not make those weights commercially cleared.
 */
class FaceSwapEngine(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var arcFaceSession: OrtSession? = null
    private var swapSession: OrtSession? = null

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    data class SessionInfo(
        val inputNames: List<String>,
        val inputShapes: Map<String, List<Long>>,
        val outputNames: List<String>,
        val outputShapes: Map<String, List<Long>>
    )

    fun sessionInfo(session: OrtSession): SessionInfo {
        val inputShapes = mutableMapOf<String, List<Long>>()
        for (name in session.inputNames) {
            val info = session.inputInfo[name]?.info
            val shape = (info as? ai.onnxruntime.TensorInfo)?.shape?.toList() ?: emptyList()
            inputShapes[name] = shape
        }
        val outputShapes = mutableMapOf<String, List<Long>>()
        for (name in session.outputNames) {
            val info = session.outputInfo[name]?.info
            val shape = (info as? ai.onnxruntime.TensorInfo)?.shape?.toList() ?: emptyList()
            outputShapes[name] = shape
        }
        return SessionInfo(
            inputNames = session.inputNames.toList(),
            inputShapes = inputShapes,
            outputNames = session.outputNames.toList(),
            outputShapes = outputShapes
        )
    }

    private fun openSession(file: File): OrtSession {
        val opts = OrtSession.SessionOptions()
        // CPU-first, by design. No NNAPI/GPU provider is set here: the
        // research report warns accelerator fallback can be slower than
        // CPU on this device class, and QNN is Snapdragon-only.
        opts.setCPUArenaAllocator(true)
        return env.createSession(file.absolutePath, opts)
    }

    private fun arcFace(): OrtSession {
        arcFaceSession?.let { return it }
        val file = ModelManager(context).modelFile(ModelManager.ARCFACE)
        if (!file.exists()) {
            throw IllegalStateException("Model ArcFace belum ada: ${file.name}. Unduh/letakkan file yang sudah diverifikasi dulu.")
        }
        return openSession(file).also { arcFaceSession = it }
    }

    private fun swapper(): OrtSession {
        swapSession?.let { return it }
        val file = ModelManager(context).modelFile(ModelManager.INSWAPPER)
        if (!file.exists()) {
            throw IllegalStateException("Model inSwapper belum ada: ${file.name}. Unduh/letakkan file yang sudah diverifikasi dulu.")
        }
        return openSession(file).also { swapSession = it }
    }

    /** Detect faces in a bitmap with ML Kit. Returns the ML Kit Face list. */
    suspend fun detectFaces(bitmap: Bitmap): List<com.google.mlkit.vision.face.Face> {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { faces -> if (cont.isActive) cont.resume(faces) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
    }

    /** Detect exactly one usable face, or throw a plain-language error. */
    suspend fun detectSingleFace(bitmap: Bitmap, label: String): FivePointFace {
        val faces = detectFaces(bitmap)
        if (faces.isEmpty()) {
            throw IllegalStateException("Tidak ada wajah terdeteksi di $label. Pakai foto frontal, terang, dan wajah cukup besar.")
        }
        if (faces.size > 1) {
            throw IllegalStateException("v1 hanya mendukung satu wajah. Di $label terdeteksi ${faces.size} wajah.")
        }
        val five = FaceLandmarks.fromMlKit(faces[0])
            ?: throw IllegalStateException("Landmark wajah di $label tidak lengkap (mata/hidung/mulut tidak semua terdeteksi). Coba foto lain.")
        if (five.boundingBoxWidth < 100f || five.boundingBoxHeight < 100f) {
            throw IllegalStateException("Wajah di $label terlalu kecil (di bawah 100px). Pakai foto yang wajahnya lebih besar.")
        }
        if (!five.isRoughlyFrontal()) {
            throw IllegalStateException("Wajah di $label bukan frontal (miring/tertutup). Pakai foto frontal yang terang.")
        }
        return five
    }

    /**
     * Compute and cache the 512-d ArcFace embedding for a source photo.
     * The embedding, not the photo, is what the swapper consumes.
     */
    suspend fun computeSourceEmbedding(sourcePhoto: Bitmap): FloatArray {
        val face = detectSingleFace(sourcePhoto, "foto identitas")
        val aligned = ImageUtils.alignFace(sourcePhoto, face, ImageUtils.EMBED_CROP)
        val session = arcFace()
        val info = sessionInfo(session)
        // Find the image input by shape: [1,3,112,112].
        val imageInput = info.inputNames.firstOrNull { name ->
            info.inputShapes[name] == listOf(1L, 3L, 112L, 112L)
        } ?: throw IllegalStateException(
            "Model ArcFace tidak punya input [1,3,112,112]. Input yang ditemukan: ${info.inputShapes}. " +
                "Periksa file modelnya — kemungkinan file salah atau bukan w600k_r50."
        )
        val floats = ImageUtils.bitmapToChwFloats(aligned)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), longArrayOf(1, 3, 112, 112))
        tensor.use {
            val result = session.run(mapOf(imageInput to it))
            result.use { res ->
                val outputName = info.outputNames.firstOrNull()
                    ?: throw IllegalStateException("Model ArcFace tidak punya output.")
                val output = res.get(outputName)?.value
                val flat: FloatArray = when (output) {
                    is Array<*> -> {
                        // Shape [1,512] comes back as Array<FloatArray>.
                        @Suppress("UNCHECKED_CAST")
                        val rows = output as Array<FloatArray>
                        if (rows.isEmpty()) throw IllegalStateException("Output ArcFace kosong.")
                        rows[0]
                    }
                    is FloatArray -> output
                    else -> throw IllegalStateException(
                        "Output ArcFace bertipe tak terduga: ${output?.javaClass?.simpleName}. " +
                            "Output yang ditemukan: ${info.outputShapes}."
                    )
                }
                if (flat.size != 512) {
                    throw IllegalStateException("Embedding ArcFace harus 512 angka, dapat ${flat.size}. Periksa file modelnya.")
                }
                return ImageUtils.l2Normalize(flat)
            }
        }
    }

    /**
     * Swap one target photo with a cached source embedding.
     * Returns a new bitmap. On any failure, throws with a plain message
     * and the caller must keep the original photo — never present the
     * unswapped photo as a swapped result.
     */
    suspend fun swapPhoto(targetPhoto: Bitmap, sourceEmbedding: FloatArray): Bitmap {
        require(sourceEmbedding.size == 512) { "Embedding sumber harus 512 angka." }
        val working = ImageUtils.downscaleForWorkingCopy(targetPhoto, 1024)
        val face = detectSingleFace(working, "foto target")
        val targetAligned = ImageUtils.alignFace(working, face, ImageUtils.SWAP_CROP)
        val session = swapper()
        val info = sessionInfo(session)

        // Classify inputs by shape, not by assumed names or order.
        val imageInput = info.inputNames.firstOrNull { name ->
            info.inputShapes[name] == listOf(1L, 3L, 128L, 128L)
        }
        val embeddingInput = info.inputNames.firstOrNull { name ->
            val shape = info.inputShapes[name]
            shape == listOf(1L, 512L) || shape == listOf(512L)
        }
        if (imageInput == null || embeddingInput == null) {
            throw IllegalStateException(
                "Model inSwapper tidak punya input gambar [1,3,128,128] + embedding [1,512]. " +
                    "Input yang ditemukan: ${info.inputShapes}. " +
                    "Periksa file modelnya — kemungkinan file salah, bukan inSwapper FP16, atau formatnya beda."
            )
        }
        val imageFloats = ImageUtils.bitmapToChwFloats(targetAligned)
        val imageTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(imageFloats), longArrayOf(1, 3, 128, 128))
        val embeddingShape = info.inputShapes[embeddingInput]!!
        val embeddingTensor = if (embeddingShape == listOf(512L)) {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(sourceEmbedding), longArrayOf(512))
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(sourceEmbedding), longArrayOf(1, 512))
        }
        val swappedCrop: Bitmap
        imageTensor.use { img ->
            embeddingTensor.use { emb ->
                val result = session.run(mapOf(imageInput to img, embeddingInput to emb))
                result.use { res ->
                    val outputName = info.outputNames.firstOrNull()
                        ?: throw IllegalStateException("Model inSwapper tidak punya output.")
                    val output = res.get(outputName)?.value
                    val outShape = info.outputShapes[outputName]
                    swappedCrop = when {
                        output is Array<*> && outShape == listOf(1L, 3L, 128L, 128L) -> {
                            @Suppress("UNCHECKED_CAST")
                            val rows = output as Array<Array<Array<FloatArray>>>
                            // rows[0] = [3][128][128]; flatten to CHW.
                            val flat = FloatArray(3 * 128 * 128)
                            for (c in 0 until 3) {
                                for (y in 0 until 128) {
                                    for (x in 0 until 128) {
                                        flat[c * 128 * 128 + y * 128 + x] = rows[0][c][y][x]
                                    }
                                }
                            }
                            ImageUtils.chwFloatsToBitmap(flat, 128, 128)
                        }
                        output is Array<*> && outShape == listOf(1L, 128L, 128L, 3L) -> {
                            @Suppress("UNCHECKED_CAST")
                            val rows = output as Array<Array<Array<FloatArray>>>
                            val flat = FloatArray(128 * 128 * 3)
                            for (y in 0 until 128) {
                                for (x in 0 until 128) {
                                    for (c in 0 until 3) {
                                        flat[(y * 128 + x) * 3 + c] = rows[0][y][x][c]
                                    }
                                }
                            }
                            ImageUtils.hwcFloatsToBitmap(flat, 128, 128)
                        }
                        else -> throw IllegalStateException(
                            "Output inSwapper bertipe/bentuk tak terduga: ${output?.javaClass?.simpleName}, " +
                                "bentuk $outShape. Bentuk yang didukung: [1,3,128,128] atau [1,128,128,3]."
                        )
                    }
                }
            }
        }
        val matched = ImageUtils.colourMatch(swappedCrop, targetAligned)
        // Paste back into the working copy. The caller saves this as the
        // swapped result and labels it AI-modified; the original file is
        // never overwritten.
        return ImageUtils.pasteBack(working, matched, face)
    }

    fun close() {
        try { arcFaceSession?.close() } catch (_: Exception) { }
        try { swapSession?.close() } catch (_: Exception) { }
        arcFaceSession = null
        swapSession = null
    }
}
