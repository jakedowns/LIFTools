package com.jakedowns.LIFTools.app.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

const val AUTHORITY = "com.jakedowns.LIFTools.app"

class DiskRepository {
    private val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL
        ) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun saveBitmap(
        filename: String,
        bitmap: Bitmap,
        context: Context
    ): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveBitmapQ(filename, bitmap, context)
    }else{
        saveBitmapLegacy(filename, bitmap, context)
    }

    @Throws(IOException::class)
    fun saveBitmapQ(
        filename: String,
        bitmap: Bitmap,
        context: Context,
        mimeType: String = "image/png",
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 95

    ): Uri {

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES+File
                .separator+"LIFToolsExports")
        }

        var uri: Uri? = null

        return runCatching {
            with(context.contentResolver) {
                insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also {
                    uri = it // Keep uri reference so it can be removed on failure

                    openOutputStream(it)?.use { stream ->
                        if (!bitmap.compress(format, quality, stream))
                            throw IOException("Failed to save bitmap.")
                    } ?: throw IOException("Failed to open output stream.")

                } ?: throw IOException("Failed to create new MediaStore record.")
            }
        }.getOrElse {
            uri?.let { orphanUri ->
                // Don't leave an orphan entry in the MediaStore
                context.contentResolver.delete(orphanUri, null, null)
            }

            throw it
        }
    }

    fun saveBitmapLegacy(
        filename: String,
        outputBitmap: Bitmap,
        context: Context
    ): Uri {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            filename
        )
//        val file = File(folder,"test.png")
        file.createNewFile()
        try {
            FileOutputStream(file, false).use { out ->
                outputBitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    out
                ) // bmp is your Bitmap instance
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/png"),
            null
        )

        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }
}