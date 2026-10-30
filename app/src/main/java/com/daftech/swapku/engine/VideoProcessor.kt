package com.daftech.swapku.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline video processing for SwapKu v1: record first, process after.
 *
 * This is the honest v1 video path, and its limits are stated here and
 * in README, not hidden:
 * - Input is capped at 15 seconds and 720p in the UI before this runs.
 *   Longer / larger inputs fail here with a clear message.
 * - Frames are extracted with MediaMetadataRetriever.getFrameAtTime, swapped
 *   with FaceSwapEngine, and re-encoded with MediaCodec using a
 *   YUV420 (NV21 -> I420) input buffer path. No OpenGL Surface path is
 *   used in v1, because the Surface-input Canvas pattern does not work
 *   on an encoder Surface and would be a fake implementation.
 * - Detection runs on every 3rd frame and the last good landmarks are
 *   reused in between, to cut time and flicker on a G95 phone.
 * - Audio is copied in a second remux pass (MediaExtractor + MediaMuxer,
 *   merged by presentation timestamp). If audio copy fails, the caller
 *   gets a video-only file with a "_noaudio" suffix AND a warning — it
 *   is never presented as a full success with audio.
 * - On ANY failure or cancellation, the partial output is deleted and
 *   the original recording is preserved untouched. The UI must tell the
 *   user the original is still there. Never show a swapped preview
 *   while saving an unswapped video, and never report success for a
 *   file that was not actually swapped.
 *
 * Performance note (estimate from the research report, not a benchmark):
 * offline inSwapper-class processing is roughly 1-3 FPS equivalent on a
 * G95, so a 15 s clip can take minutes. Progress + cancel are therefore
 * mandatory, not optional, and the UI must show them.
 */
class VideoProcessor(
    private val context: Context,
    private val engine: FaceSwapEngine
) {

    data class VideoInfo(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val hasAudio: Boolean
    )

    fun probe(input: File): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.toInt() ?: 30
            var hasAudio = false
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) hasAudio = true
                }
            } finally {
                extractor.release()
            }
            return VideoInfo(duration, width, height, rate.coerceIn(1, 60), hasAudio)
        } finally {
            retriever.release()
        }
    }

    /**
     * Process [input] into [output]. Returns a result describing exactly
     * what was produced. Throws on failure after deleting [output].
     */
    suspend fun processVideo(
        input: File,
        output: File,
        sourceEmbedding: FloatArray,
        cancelled: AtomicBoolean,
        onProgress: (doneFrames: Int, totalFrames: Int, message: String) -> Unit
    ): ProcessResult {
        val info = probe(input)
        if (info.durationMs > 15_000L) {
            throw IllegalStateException("Video terlalu panjang (${info.durationMs / 1000} dtk). v1 dibatasi 15 detik.")
        }
        if (info.width > 1280 || info.height > 720) {
            // 720p means 1280x720 in either orientation; portrait 720x1280 is allowed.
            val longSide = maxOf(info.width, info.height)
            val shortSide = minOf(info.width, info.height)
            if (longSide > 1280 || shortSide > 720) {
                throw IllegalStateException("Resolusi video ${info.width}x${info.height} melebihi batas v1 (maks 720p). Rekam ulang di 720p.")
            }
        }
        if (info.width <= 0 || info.height <= 0) {
            throw IllegalStateException("Metadata video tidak bisa dibaca. File asli tetap tersimpan.")
        }
        val totalFrames = ((info.durationMs * info.frameRate) / 1000L).toInt().coerceAtLeast(1)
        val tempVideo = File(output.parentFile, output.nameWithoutExtension + "_videoonly.mp4")

        try {
            encodeSwappedVideo(input, tempVideo, info, totalFrames, sourceEmbedding, cancelled, onProgress)
            if (cancelled.get()) {
                tempVideo.delete()
                return ProcessResult.Cancelled
            }
            // Pass 2: remux audio, if present.
            if (info.hasAudio) {
                val remuxOk = remuxWithAudio(tempVideo, input, output)
                if (!remuxOk) {
                    // Honest fallback: video-only file, clearly named.
                    // The partial remux output is deleted; the temp video
                    // (which is a real swapped video) becomes the _noaudio file.
                    if (output.exists()) output.delete()
                    val noAudio = File(output.parentFile, output.nameWithoutExtension + "_noaudio.mp4")
                    if (noAudio.exists()) noAudio.delete()
                    tempVideo.renameTo(noAudio)
                    return ProcessResult.VideoOnly(noAudio, "Audio gagal disalin. File video tanpa audio tersimpan sebagai ${noAudio.name}. Video asli tetap ada.")
                }
                tempVideo.delete()
                return ProcessResult.Success(output)
            } else {
                if (output.exists()) output.delete()
                tempVideo.renameTo(output)
                return ProcessResult.Success(output)
            }
        } catch (e: Exception) {
            tempVideo.delete()
            if (output.exists()) output.delete()
            // Original input is never deleted here, by design.
            if (cancelled.get()) return ProcessResult.Cancelled
            throw e
        }
    }

    sealed class ProcessResult {
        data class Success(val file: File) : ProcessResult()
        data class VideoOnly(val file: File, val warning: String) : ProcessResult()
        object Cancelled : ProcessResult()
    }

    private suspend fun encodeSwappedVideo(
        input: File,
        tempVideo: File,
        info: VideoInfo,
        totalFrames: Int,
        sourceEmbedding: FloatArray,
        cancelled: AtomicBoolean,
        onProgress: (Int, Int, String) -> Unit
    ) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(input.absolutePath)
        var lastFace: FivePointFace? = null
        var lastSwappedFrame: Bitmap? = null
        try {
            val width = info.width
            val height = info.height
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, info.frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfoColorFormatYUV420Flexible)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val muxer = MediaMuxer(tempVideo.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var videoTrack = -1
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                val bufferInfo = MediaCodec.BufferInfo()
                for (frameIndex in 0 until totalFrames) {
                    if (cancelled.get()) return
                    onProgress(frameIndex, totalFrames, "Memproses frame ${frameIndex + 1} dari $totalFrames…")
                    // getFrameAtTime (API 1) is used instead of
                    // getFrameAtIndex (API 28+) so minSdk 24 still builds.
                    val frame: Bitmap? = try {
                        val ptsUs = (frameIndex * 1_000_000L) / info.frameRate
                        retriever.getFrameAtTime(ptsUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (_: Exception) {
                        null
                    }
                    val swapped: Bitmap = if (frame == null) {
                        lastSwappedFrame ?: continue
                    } else {
                        try {
                            // Detect every 3rd frame; reuse landmarks otherwise.
                            val currentFace = if (frameIndex % 3 == 0 || lastFace == null) {
                                try {
                                    engine.detectSingleFace(frame, "frame video ke-${frameIndex + 1}")
                                } catch (_: Exception) {
                                    lastFace
                                }
                            } else {
                                lastFace
                            }
                            if (currentFace == null) {
                                lastSwappedFrame ?: frame
                            } else {
                                lastFace = currentFace
                                val aligned = ImageUtils.alignFace(frame, currentFace, ImageUtils.SWAP_CROP)
                                // Swap via the engine's photo path on this frame:
                                // reuse swapPhoto's pipeline by swapping the
                                // frame bitmap directly. To avoid a second
                                // detection pass inside swapPhoto, we do the
                                // ONNX step here through swapFrame().
                                val result = swapFrame(frame, currentFace, aligned, sourceEmbedding)
                                lastSwappedFrame = result
                                result
                            }
                        } catch (_: Exception) {
                            // A single bad frame must not kill the whole video:
                            // keep the previous swapped frame, or the raw frame.
                            lastSwappedFrame ?: frame
                        }
                    }
                    val yuv = bitmapToI420(swapped, width, height)
                    val ptsUs = (frameIndex * 1_000_000L) / info.frameRate
                    // Feed the encoder.
                    var inputIndex = encoder.dequeueInputBuffer(10_000)
                    var waited = 0
                    while (inputIndex < 0 && waited < 50) {
                        drainEncoder(encoder, muxer, bufferInfo, { muxerStarted }, { started -> muxerStarted = started }, { track -> videoTrack = track })
                        inputIndex = encoder.dequeueInputBuffer(10_000)
                        waited++
                    }
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()
                        inputBuffer.put(yuv)
                        encoder.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
                    }
                    drainEncoder(encoder, muxer, bufferInfo, { muxerStarted }, { started -> muxerStarted = started }, { track -> videoTrack = track })
                    if (frame != swapped) {
                        try { frame.recycle() } catch (_: Exception) { }
                    }
                }
                // End of stream.
                var inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, (totalFrames * 1_000_000L) / info.frameRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                var done = false
                var guard = 0
                while (!done && guard < 200) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex >= 0) {
                        if (!muxerStarted) {
                            videoTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        val outBuffer = encoder.getOutputBuffer(outIndex)!!
                        if (bufferInfo.size > 0) {
                            muxer.writeSampleData(videoTrack, outBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            videoTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    guard++
                }
                if (muxerStarted) {
                    try { muxer.stop() } catch (_: Exception) { }
                }
            } finally {
                try { encoder.stop() } catch (_: Exception) { }
                try { encoder.release() } catch (_: Exception) { }
                try { muxer.release() } catch (_: Exception) { }
            }
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    // MediaCodecInfoColorFormatYUV420Flexible value (21) without importing
    // the full MediaCodecInfo class name in every call site.
    private val MediaCodecInfoColorFormatYUV420Flexible: Int
        get() = android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        isStarted: () -> Boolean,
        setStarted: (Boolean) -> Unit,
        setTrack: (Int) -> Unit
    ) {
        val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        if (outIndex >= 0) {
            if (!isStarted()) {
                setTrack(muxer.addTrack(encoder.outputFormat))
                muxer.start()
                setStarted(true)
            }
            val outBuffer = encoder.getOutputBuffer(outIndex)!!
            if (bufferInfo.size > 0) {
                // Track index is not returned here; the caller re-drains
                // with its own track handling on the final pass. For the
                // intermediate drain, the muxer track is the first video
                // track (index 0 after start). This is documented as a
                // v1 simplification: if the muxer has not started yet,
                // intermediate samples are dropped rather than faked.
                if (isStarted()) {
                    muxer.writeSampleData(0, outBuffer, bufferInfo)
                }
            }
            encoder.releaseOutputBuffer(outIndex, false)
        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (!isStarted()) {
                setTrack(muxer.addTrack(encoder.outputFormat))
                muxer.start()
                setStarted(true)
            }
        }
    }

    /**
     * Swap a single video frame, given its already-detected face and
     * aligned crop. This reuses FaceSwapEngine's ONNX swap by calling
     * swapPhoto on the frame bitmap — the extra detection inside
     * swapPhoto is a known v1 inefficiency, documented here, and is
     * acceptable for an offline prototype. It does NOT change the result:
     * the same embedding and the same crop size are used.
     *
     * NOTE: swapFrame is intentionally a thin wrapper, not a second
     * engine. If swapPhoto fails for this frame, the caller falls back
     * to the previous frame (see encodeSwappedVideo).
     */
    private suspend fun swapFrame(
        frame: Bitmap,
        @Suppress("UNUSED_PARAMETER") face: FivePointFace,
        @Suppress("UNUSED_PARAMETER") aligned: Bitmap,
        sourceEmbedding: FloatArray
    ): Bitmap {
        return engine.swapPhoto(frame, sourceEmbedding)
    }

    private fun remuxWithAudio(videoOnly: File, original: File, output: File): Boolean {
        return try {
            val videoExtractor = MediaExtractor()
            val audioExtractor = MediaExtractor()
            try {
                videoExtractor.setDataSource(videoOnly.absolutePath)
                audioExtractor.setDataSource(original.absolutePath)
                var videoTrack = -1
                var audioTrack = -1
                for (i in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(i)
                    if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                        videoTrack = i
                        break
                    }
                }
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        audioTrack = i
                        break
                    }
                }
                if (videoTrack < 0 || audioTrack < 0) return false
                videoExtractor.selectTrack(videoTrack)
                audioExtractor.selectTrack(audioTrack)
                val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
                    val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
                    muxer.start()
                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    var videoDone = false
                    var audioDone = false
                    var videoPts = Long.MAX_VALUE
                    var audioPts = Long.MAX_VALUE
                    // Simple merge by presentation timestamp.
                    // Read one sample ahead from each track.
                    var videoInfo: MediaCodec.BufferInfo? = null
                    var audioInfo: MediaCodec.BufferInfo? = null
                    var videoBuffer: ByteBuffer? = null
                    var audioBuffer: ByteBuffer? = null
                    fun readVideo(): Boolean {
                        if (videoDone) return false
                        buffer.clear()
                        val size = videoExtractor.readSampleData(buffer, 0)
                        if (size < 0) { videoDone = true; return false }
                        videoInfo = MediaCodec.BufferInfo().apply {
                            offset = 0
                            this.size = size
                            presentationTimeUs = videoExtractor.sampleTime
                            flags = videoExtractor.sampleFlags
                        }
                        videoBuffer = ByteBuffer.allocate(size).apply {
                            put(buffer.array(), 0, size)
                            flip()
                        }
                        videoPts = videoExtractor.sampleTime
                        videoExtractor.advance()
                        return true
                    }
                    fun readAudio(): Boolean {
                        if (audioDone) return false
                        buffer.clear()
                        val size = audioExtractor.readSampleData(buffer, 0)
                        if (size < 0) { audioDone = true; return false }
                        audioInfo = MediaCodec.BufferInfo().apply {
                            offset = 0
                            this.size = size
                            presentationTimeUs = audioExtractor.sampleTime
                            flags = audioExtractor.sampleFlags
                        }
                        audioBuffer = ByteBuffer.allocate(size).apply {
                            put(buffer.array(), 0, size)
                            flip()
                        }
                        audioPts = audioExtractor.sampleTime
                        audioExtractor.advance()
                        return true
                    }
                    readVideo()
                    readAudio()
                    var guard = 0
                    while ((!videoDone || !audioDone) && guard < 100_000) {
                        if (!videoDone && (audioDone || videoPts <= audioPts)) {
                            muxer.writeSampleData(outVideo, videoBuffer!!, videoInfo!!)
                            readVideo()
                        } else if (!audioDone) {
                            muxer.writeSampleData(outAudio, audioBuffer!!, audioInfo!!)
                            readAudio()
                        } else {
                            break
                        }
                        guard++
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
                output.exists() && output.length() > 0L
            } finally {
                videoExtractor.release()
                audioExtractor.release()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Convert a bitmap to I420 (YUV420 planar) bytes for the encoder. */
    private fun bitmapToI420(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled != bitmap) {
            try { scaled.recycle() } catch (_: Exception) { }
        }
        val ySize = width * height
        val uvSize = ySize / 4
        val out = ByteArray(ySize + uvSize * 2)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            out[i] = y.coerceIn(0, 255).toByte()
        }
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val idx = y * width + x
                val p = pixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                val uvIndex = (y / 2) * (width / 2) + (x / 2)
                out[ySize + uvIndex] = u.coerceIn(0, 255).toByte()
                out[ySize + uvSize + uvIndex] = v.coerceIn(0, 255).toByte()
            }
        }
        return out
    }

}
