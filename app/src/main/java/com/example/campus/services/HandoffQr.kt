package com.example.campus.services

import android.graphics.Bitmap
import com.example.campus.R
import androidx.activity.ComponentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Renders the handoff value as data. QR contents are never treated as links or commands. */
object HandoffQr {
    fun bitmap(code: String, size: Int = 512): Bitmap {
        val matrix = encodedMatrix(code, size)
        val pixels = IntArray(size * size) { position ->
            if (matrix[position % size, position / size]) 0xFF183F32.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    internal fun encodedMatrix(code: String, size: Int = 512): BitMatrix {
        require(code.isNotBlank() && code.length <= 512) { "A handoff code must contain between 1 and 512 characters." }
        require(size in 128..2048) { "QR image size must be between 128 and 2048 pixels." }
        return QRCodeWriter().encode(
            code, BarcodeFormat.QR_CODE, size, size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 4,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
        )
    }
}

/** Register in onCreate (before STARTED), then launch from the handoff action. */
class HandoffScanner(private val activity: ComponentActivity, onCode: (String) -> Unit) {
    private val launcher = activity.registerForActivityResult(ScanContract()) { result ->
        val code = result.contents
        if (!code.isNullOrBlank() && code.length <= 512) onCode(code)
    }

    fun launch() {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(activity.getString(R.string.scan_handoff))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }
}
