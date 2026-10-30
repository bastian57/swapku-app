package com.daftech.swapku.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Saving swapped results. Rules:
 * - The original photo/video is never overwritten. Swapped outputs get
 *   their own file, with "_AI-modified" in the name, in Download/SwapKu.
 * - Bitmaps are saved as JPEG (photo) via MediaStore so they appear in
 *   the gallery. Videos are copied via MediaStore Files (video/mp4).
 * - No upload, no share intent to another app's camera, no cloud.
 */
object StorageHelper {

    fun saveSwappedPhoto(context: Context, bitmap: Bitmap, baseName: String): Uri? {
        val name = baseName + "_AI-modified.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SwapKu")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return uri
    }

    fun saveSwappedVideo(context: Context, sourceFile: File, baseName: String): Uri? {
        val name = baseName + "_AI-modified.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SwapKu")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        return uri
    }

    fun copyUriToCache(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val outFile = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile
        } catch (_: Exception) {
            null
        }
    }
}
