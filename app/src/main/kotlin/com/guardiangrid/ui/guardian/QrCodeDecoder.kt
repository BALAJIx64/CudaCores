package com.guardiangrid.ui.guardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Offline ZXing QR decoder for camera captures and shared QR screenshots.
 */
object QrCodeDecoder {

    fun decodeFromUri(context: Context, imageUri: Uri): Result<String> {
        return try {
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return Result.failure(IllegalArgumentException("Unable to read image"))
            decodeFromBitmap(bitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap): Result<String> {
        val scaled = downscaleIfHuge(bitmap, 1280)
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
        return try {
            val result = MultiFormatReader().decode(binaryBitmap, hints)
            Result.success(result.text)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("No readable QR code found in image. Move closer or try pasting the URI."))
        }
    }

    private fun downscaleIfHuge(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
