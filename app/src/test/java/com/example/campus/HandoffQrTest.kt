package com.example.campus

import com.example.campus.services.HandoffQr
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HandoffQrTest {
    @Test fun generatedCodesRoundTripThroughRealQrDecoder() {
        listOf("482019", "8d669e66-50d3-4143-b855-48acb9754839", "HASH_88321_TEMP", "umamukeli-✓").forEach { code ->
            val matrix = HandoffQr.encodedMatrix(code)
            val pixels = IntArray(matrix.width * matrix.height) { position ->
                if (matrix[position % matrix.width, position / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))
            assertEquals(code, decoded.text)
        }
    }

    @Test fun blankAndOversizedCodesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { HandoffQr.encodedMatrix(" ") }
        assertThrows(IllegalArgumentException::class.java) { HandoffQr.encodedMatrix("a".repeat(513)) }
        assertThrows(IllegalArgumentException::class.java) { HandoffQr.encodedMatrix("482019", 4096) }
    }
}
