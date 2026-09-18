package com.example.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.InputStream

object ImageSourceHelper {
    private const val TAG = "ImageSourceHelper"

    /**
     * Safely loads and scales an image from Uri to target resolution (default 1280x720).
     */
    fun loadImage(
        context: Context,
        uri: Uri,
        targetWidth: Int = 1280,
        targetHeight: Int = 720
    ): StudioSource.Image? {
        return try {
            val fileName = getFileName(context, uri)
            val orientation = getExifOrientation(context, uri)

            // Step 1: Decode bounds to calculate inSampleSize
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) {
                Log.e(TAG, "Invalid image dimensions: ${rawWidth}x$rawHeight")
                return null
            }

            // Step 2: Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, targetWidth * 2, targetHeight * 2)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            var decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            // Step 3: Handle rotation
            if (orientation != 0) {
                val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    decodedBitmap,
                    0,
                    0,
                    decodedBitmap.width,
                    decodedBitmap.height,
                    matrix,
                    true
                )
                if (rotated != decodedBitmap) {
                    decodedBitmap.recycle()
                    decodedBitmap = rotated
                }
            }

            // Step 4: Scale & letterbox to exact target canvas (e.g. 1280x720 16:9)
            val finalBitmap = createLetterboxedBitmap(decodedBitmap, targetWidth, targetHeight)
            if (finalBitmap != decodedBitmap) {
                decodedBitmap.recycle()
            }

            // Step 5: Pre-generate NV21 frame cache for smooth zero-allocation streaming
            val nv21Cache = bitmapToNv21(finalBitmap, targetWidth, targetHeight)

            StudioSource.Image(
                uri = uri,
                bitmap = finalBitmap,
                name = fileName,
                nv21Cache = nv21Cache
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load image from URI $uri: ${e.message}", e)
            null
        }
    }

    fun getFileName(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query file name: ${e.message}")
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Image"
    }

    private fun getExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun createLetterboxedBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val dstW = targetWidth.toFloat()
        val dstH = targetHeight.toFloat()

        val scale = minOf(dstW / srcW, dstH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale

        val left = (dstW - scaledW) / 2f
        val top = (dstH - scaledH) / 2f

        val destRect = RectF(left, top, left + scaledW, top + scaledH)
        val srcRect = Rect(0, 0, source.width, source.height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(source, srcRect, destRect, paint)
        return output
    }

    /**
     * Converts an ARGB_8888 Bitmap to NV21 byte array (YUV420SemiPlanar).
     */
    fun bitmapToNv21(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val nv21 = ByteArray(width * height * 3 / 2)
        val argb = IntArray(width * height)

        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        scaled.getPixels(argb, 0, width, 0, 0, width, height)

        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index++]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                // RGB to YUV standard formula
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                nv21[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    nv21[uvIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    nv21[uvIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                }
            }
        }

        if (scaled != bitmap) {
            scaled.recycle()
        }

        return nv21
    }

    /**
     * Blends two NV21 frames with alpha (0.0 = sourceA, 1.0 = sourceB).
     */
    fun blendNv21(
        sourceA: ByteArray,
        sourceB: ByteArray,
        alpha: Float,
        out: ByteArray,
        width: Int,
        height: Int
    ) {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        val invAlpha = 1f - clampedAlpha
        val totalBytes = width * height * 3 / 2
        val len = minOf(totalBytes, minOf(sourceA.size, sourceB.size, out.size))

        for (i in 0 until len) {
            val valA = sourceA[i].toInt() and 0xFF
            val valB = sourceB[i].toInt() and 0xFF
            val blended = (valA * invAlpha + valB * clampedAlpha).toInt().coerceIn(0, 255)
            out[i] = blended.toByte()
        }
    }
}
