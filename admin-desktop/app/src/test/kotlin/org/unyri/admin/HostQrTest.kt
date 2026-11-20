package org.unyri.admin

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import kotlin.test.assertEquals

class HostQrTest {
    @Test fun phoneQrRoundTripsOnlyTheHttpsAddress() {
        val origin = "https://family.example.org"
        val matrix = QRCodeWriter().encode(origin, BarcodeFormat.QR_CODE, 200, 200)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        assertEquals(origin, MultiFormatReader().decode(bitmap).text)
    }
}
