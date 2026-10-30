package com.daftech.swapku.engine

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * CameraX helper for SwapKu v1.
 *
 * What this does, and what it does NOT do:
 * - Preview: shows the REAL camera in PreviewView. No swap is applied
 *   to the preview in v1. The UI must never label the preview as a
 *   swapped preview.
 * - Photo capture: ImageCapture saves a normal photo. That photo can
 *   then be swapped offline by FaceSwapEngine.
 * - Video recording: VideoCapture/Recorder saves a normal video, capped
 *   at 15 seconds by the ViewModel timer (the recorder itself is stopped
 *   by the caller). That video can then be processed offline by
 *   VideoProcessor. The recorded file is NEVER a swapped video.
 *
 * There is no virtual-camera, no Surface redirection, no injection
 * into any other app in this file. That exclusion is deliberate.
 */
class CameraHelper(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null

    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (e: Exception) {
                onError("Kamera gagal dibuka: ${e.message ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        try { provider?.unbindAll() } catch (_: Exception) { }
    }

    fun capturePhoto(onResult: (File?, String?) -> Unit) {
        val capture = imageCapture ?: run {
            onResult(null, "Kamera belum siap. Tunggu preview muncul dulu.")
            return
        }
        val photoFile = File(
            context.cacheDir,
            "swapku_photo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onResult(photoFile, null)
                }

                override fun onError(exception: ImageCaptureException) {
                    onResult(null, "Foto gagal diambil: ${exception.message}")
                }
            }
        )
    }

    fun startVideoRecording(onEvent: (VideoRecordEvent) -> Unit, onError: (String) -> Unit) {
        val capture = videoCapture ?: run {
            onError("Video belum siap. Tunggu preview muncul dulu.")
            return
        }
        val videoFile = File(
            context.cacheDir,
            "swapku_video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        )
        val outputOptions = FileOutputOptions.Builder(videoFile).build()
        val executor: Executor = ContextCompat.getMainExecutor(context)
        try {
            recording = capture.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(executor, onEvent)
        } catch (e: Exception) {
            onError("Rekaman gagal dimulai: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stopVideoRecording() {
        try { recording?.stop() } catch (_: Exception) { }
        recording = null
    }

    companion object {
        fun uriFromFile(file: File): Uri = Uri.fromFile(file)
    }
}
