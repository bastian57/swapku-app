package com.daftech.swapku

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.daftech.swapku.ui.MainScreen
import com.daftech.swapku.ui.SwapKuTheme

/**
 * SwapKu entry point. Permissions are requested here; the camera
 * preview itself lives in MainScreen via CameraHelper.
 *
 * The two photo pickers are registered here (not in MainScreen) so
 * their launchers survive recomposition. They decode the picked image
 * to a Bitmap and hand it to the ViewModel through selectSourceBitmap /
 * selectTargetBitmap. No image is uploaded anywhere.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private val pickSourcePhoto = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { decodeUriToBitmap(it)?.let { bitmap -> viewModel.selectSourceBitmap(bitmap) } }
    }

    private val pickTargetPhoto = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { decodeUriToBitmap(it)?.let { bitmap -> viewModel.selectTargetBitmap(bitmap) } }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions are re-checked by the camera helper / UI state.
        // No action is forced here; the UI shows a message if denied.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }
        viewModel.refreshModelStatus()
        setContent {
            SwapKuTheme {
                MainScreen(
                    viewModel = viewModel,
                    onPickSourcePhoto = { pickSourcePhoto.launch("image/*") },
                    onPickTargetPhoto = { pickTargetPhoto.launch("image/*") }
                )
            }
        }
    }

    private fun decodeUriToBitmap(uri: Uri): android.graphics.Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }
}
