package com.jakedowns.LIFTools.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory
import android.graphics.Matrix;
import android.media.ExifInterface
import android.net.Uri
import android.os.Build;
import android.provider.MediaStore

object ExifUtil {
    /**
     * @see http://sylvana.net/jpegcrop/exif_orientation.html
     * @todo: use JNI for c++ rotations
     */
    fun rotateBitmap(src: String, bitmap: Bitmap): Bitmap {
        try {
            val orientation = getExifOrientation(src)
            if (orientation == 1) {
                return bitmap
            }
            val matrix: android.graphics.Matrix = android.graphics.Matrix()
            when (orientation) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> {
                    matrix.setRotate(180f)
                    matrix.postScale(-1f, 1f)
                }
                5 -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                6 -> matrix.setRotate(90f)
                7 -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                8 -> matrix.setRotate(-90f)
                else -> return bitmap
            }
            return try {
                val oriented: Bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true)
                bitmap.recycle()
                oriented
            } catch (e: java.lang.OutOfMemoryError) {
                e.printStackTrace()
                bitmap
            }
        } catch (e: java.io.IOException) {
            e.printStackTrace()
        }
        return bitmap
    }

    @Throws(java.io.IOException::class)
    private fun getExifOrientation(src: String): Int {
        var orientation = 1
        try {
            val exif = ExifInterface(src)
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return orientation
    }

    @SuppressLint("Recycle")
    fun getOrientation(context: Context, photoUri: Uri?): Int {
        /* it's on the external media. */
        val cursor: Cursor? = context.contentResolver.query(photoUri!!, arrayOf(MediaStore.Images.ImageColumns.ORIENTATION), null, null, null)
        if (cursor == null || cursor.count != 1) {
            return -1
        }
        cursor.moveToFirst()
        return cursor.getInt(0)
    }

    @Throws(IOException::class)
    fun getCorrectlyOrientedImage(context: Context, photoUri: Uri?, maxImageDimension: Int): Bitmap? {
        var inputStream = context.contentResolver.openInputStream(photoUri!!)
        val dbo = BitmapFactory.Options()
        dbo.inJustDecodeBounds = true
        BitmapFactory.decodeStream(inputStream, null, dbo)
        inputStream?.close()

        val rotatedWidth: Int
        val rotatedHeight: Int
        val orientation = getOrientation(context, photoUri)
        if (orientation == 90 || orientation == 270) {
            rotatedWidth = dbo.outHeight
            rotatedHeight = dbo.outWidth
        } else {
            rotatedWidth = dbo.outWidth
            rotatedHeight = dbo.outHeight
        }
        var srcBitmap: Bitmap?
        inputStream = context.contentResolver.openInputStream(photoUri)
        if (rotatedWidth > maxImageDimension || rotatedHeight > maxImageDimension) {
            val widthRatio = rotatedWidth.toFloat() / maxImageDimension as Float
            val heightRatio = rotatedHeight.toFloat() / maxImageDimension as Float
            val maxRatio = kotlin.math.max(widthRatio, heightRatio)

            // Create the bitmap from file
            val options = BitmapFactory.Options()
            options.inSampleSize = maxRatio.toInt()
            srcBitmap = BitmapFactory.decodeStream(inputStream, null, options)
        } else {
            srcBitmap = BitmapFactory.decodeStream(inputStream)
        }
        inputStream?.close()

        /*
         * if the orientation is not 0 (or -1, which means we don't know), we
         * have to do a rotation.
         */
        if (orientation > 0) {
            val matrix = Matrix()
            matrix.postRotate(orientation.toFloat())
            srcBitmap = Bitmap.createBitmap(
                srcBitmap!!, 0, 0, srcBitmap.width,
                srcBitmap.height, matrix, true
            )
        }
        return srcBitmap
    }
}