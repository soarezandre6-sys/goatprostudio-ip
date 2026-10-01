package com.goatpro.ip

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object ImageUtils {
    private const val TARGET_ASPECT = 16f / 9f

    fun imageProxyToJpeg(image: ImageProxy, quality: Int = 72): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val nv21 = yuv420ToNv21(image)
        val output = ByteArrayOutputStream()
        val crop = centeredLandscape16x9Crop(image.width, image.height)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)

        val ok = yuvImage.compressToJpeg(crop, quality, output)
        return if (ok) output.toByteArray() else null
    }

    /**
     * CameraX ImageAnalysis may negotiate a 4:3 YUV buffer even when 1080p/720p was
     * requested. GOAT PRO IP always exposes a landscape 16:9 MJPEG stream, so crop the
     * sensor buffer centrally instead of forwarding the sensor aspect ratio to the PC.
     *
     * JPEG/NV21 crop coordinates are kept even to preserve chroma alignment.
     */
    private fun centeredLandscape16x9Crop(width: Int, height: Int): Rect {
        if (width <= 0 || height <= 0 || width < height) {
            return Rect(0, 0, width, height)
        }

        val currentAspect = width.toFloat() / height.toFloat()
        var cropWidth = width
        var cropHeight = height

        if (currentAspect < TARGET_ASPECT) {
            cropHeight = (width / TARGET_ASPECT).roundToInt()
        } else if (currentAspect > TARGET_ASPECT) {
            cropWidth = (height * TARGET_ASPECT).roundToInt()
        }

        cropWidth = cropWidth.coerceIn(2, width) and -2
        cropHeight = cropHeight.coerceIn(2, height) and -2

        val left = ((width - cropWidth) / 2) and -2
        val top = ((height - cropHeight) / 2) and -2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val out = ByteArray(ySize + uvSize * 2)

        copyPlane(image.planes[0], width, height, out, 0, 1)

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var offset = ySize
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            val uRow = row * uRowStride
            val vRow = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRow + col * uPixelStride
                val vIndex = vRow + col * vPixelStride
                out[offset++] = vBuffer.get(vIndex)
                out[offset++] = uBuffer.get(uIndex)
            }
        }
        return out
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outputPos = outputOffset
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                output[outputPos] = buffer.get(rowStart + col * pixelStride)
                outputPos += outputPixelStride
            }
        }
    }
}
