package com.goatpro.ip

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream

object ImageUtils {
    private const val TARGET_ASPECT = 16f / 9f

    data class JpegFrame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    )

    fun imageProxyToJpeg(
        image: ImageProxy,
        quality: Int = 85,
        rotationDegrees: Int = image.imageInfo.rotationDegrees
    ): JpegFrame? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val nv21 = yuv420ToNv21(image)
        val crop = centeredLandscape16x9Crop(image.width, image.height)
        val cropped = cropNv21(nv21, image.width, image.height, crop)

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val rotated = rotateNv21(
            cropped.bytes,
            cropped.width,
            cropped.height,
            normalizedRotation
        )

        val output = ByteArrayOutputStream(
            (rotated.width * rotated.height / 3).coerceAtLeast(64 * 1024)
        )
        val yuvImage = YuvImage(
            rotated.bytes,
            ImageFormat.NV21,
            rotated.width,
            rotated.height,
            null
        )

        val ok = yuvImage.compressToJpeg(
            Rect(0, 0, rotated.width, rotated.height),
            quality.coerceIn(55, 97),
            output
        )
        return if (ok) {
            JpegFrame(output.toByteArray(), rotated.width, rotated.height)
        } else {
            null
        }
    }

    private data class Nv21Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    )

    /**
     * CameraX can negotiate a 4:3 analysis buffer even when FHD/HD is requested.
     * Crop centrally to 16:9 before encoding so the stream never becomes square-ish.
     */
    private fun centeredLandscape16x9Crop(width: Int, height: Int): Rect {
        if (width <= 0 || height <= 0) return Rect(0, 0, width, height)

        var cropWidth = width and -2
        var cropHeight = height and -2
        val currentAspect = cropWidth.toFloat() / cropHeight.toFloat()

        if (currentAspect < TARGET_ASPECT) {
            cropHeight = (cropWidth / TARGET_ASPECT).roundToInt().coerceAtMost(cropHeight) and -2
        } else if (currentAspect > TARGET_ASPECT) {
            cropWidth = (cropHeight * TARGET_ASPECT).roundToInt().coerceAtMost(cropWidth) and -2
        }

        cropWidth = cropWidth.coerceAtLeast(2)
        cropHeight = cropHeight.coerceAtLeast(2)

        val left = ((width - cropWidth) / 2) and -2
        val top = ((height - cropHeight) / 2) and -2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun cropNv21(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        crop: Rect
    ): Nv21Frame {
        val cropWidth = crop.width() and -2
        val cropHeight = crop.height() and -2
        if (crop.left == 0 && crop.top == 0 &&
            cropWidth == sourceWidth && cropHeight == sourceHeight
        ) {
            return Nv21Frame(source, sourceWidth, sourceHeight)
        }

        val output = ByteArray(cropWidth * cropHeight * 3 / 2)
        val sourceYSize = sourceWidth * sourceHeight
        val destYSize = cropWidth * cropHeight

        for (row in 0 until cropHeight) {
            val srcOffset = (crop.top + row) * sourceWidth + crop.left
            val dstOffset = row * cropWidth
            System.arraycopy(source, srcOffset, output, dstOffset, cropWidth)
        }

        for (row in 0 until cropHeight / 2) {
            val srcOffset = sourceYSize + (crop.top / 2 + row) * sourceWidth + crop.left
            val dstOffset = destYSize + row * cropWidth
            System.arraycopy(source, srcOffset, output, dstOffset, cropWidth)
        }

        return Nv21Frame(output, cropWidth, cropHeight)
    }

    /**
     * Rotates NV21 without decoding to Bitmap/JPEG first. This avoids a second lossy JPEG
     * pass and keeps latency lower while supporting vertical phone use.
     */
    private fun rotateNv21(
        source: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Nv21Frame {
        if (rotationDegrees == 0) return Nv21Frame(source, width, height)
        if (rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) {
            return Nv21Frame(source, width, height)
        }

        val newWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val newHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
        val output = ByteArray(source.size)

        // Y plane
        for (y in 0 until height) {
            for (x in 0 until width) {
                val src = y * width + x
                val dst = when (rotationDegrees) {
                    90 -> x * newWidth + (height - 1 - y)
                    180 -> (height - 1 - y) * newWidth + (width - 1 - x)
                    else -> (width - 1 - x) * newWidth + y // 270
                }
                output[dst] = source[src]
            }
        }

        // Interleaved VU plane, handled as 2x2 chroma blocks.
        val srcYSize = width * height
        val dstYSize = newWidth * newHeight
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val newChromaWidth = newWidth / 2

        for (by in 0 until chromaHeight) {
            for (bx in 0 until chromaWidth) {
                val src = srcYSize + by * width + bx * 2
                val dstBx: Int
                val dstBy: Int
                when (rotationDegrees) {
                    90 -> {
                        dstBx = chromaHeight - 1 - by
                        dstBy = bx
                    }
                    180 -> {
                        dstBx = chromaWidth - 1 - bx
                        dstBy = chromaHeight - 1 - by
                    }
                    else -> {
                        dstBx = by
                        dstBy = chromaWidth - 1 - bx
                    }
                }
                val dst = dstYSize + dstBy * (newChromaWidth * 2) + dstBx * 2
                output[dst] = source[src]
                output[dst + 1] = source[src + 1]
            }
        }

        return Nv21Frame(output, newWidth, newHeight)
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

        // Most Camera2 devices expose YUV_420_888 chroma as an interleaved VU buffer
        // (NV21-compatible) with pixelStride=2. Detect that layout and bulk-copy rows
        // instead of doing ~500k indexed ByteBuffer reads per Full-HD frame.
        if (uPixelStride == 2 && vPixelStride == 2 &&
            uRowStride == vRowStride &&
            looksLikeInterleavedVu(uBuffer, vBuffer, uRowStride, chromaWidth, chromaHeight)
        ) {
            val v = vBuffer.duplicate()
            for (row in 0 until chromaHeight) {
                val rowStart = row * vRowStride
                val wanted = width
                val available = (v.limit() - rowStart).coerceAtLeast(0)
                val copy = minOf(wanted, available)
                if (copy > 0) {
                    v.position(rowStart)
                    v.get(out, offset, copy)
                    offset += copy
                }
                if (copy < wanted) {
                    // The final interleaved U byte can be outside the V-plane view.
                    var col = copy / 2
                    if ((copy and 1) != 0) {
                        out[offset++] = uBuffer.get(row * uRowStride + col * uPixelStride)
                        col++
                    }
                    while (col < chromaWidth) {
                        out[offset++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                        out[offset++] = uBuffer.get(row * uRowStride + col * uPixelStride)
                        col++
                    }
                }
            }
            return out
        }

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

    private fun looksLikeInterleavedVu(
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        rowStride: Int,
        chromaWidth: Int,
        chromaHeight: Int
    ): Boolean {
        if (chromaWidth < 2 || chromaHeight < 1) return false
        return try {
            val rowsToCheck = minOf(chromaHeight, 2)
            val cols = intArrayOf(0, chromaWidth / 3, (chromaWidth * 2) / 3, chromaWidth - 2)
                .map { it.coerceIn(0, chromaWidth - 1) }
                .distinct()

            var matches = 0
            var samples = 0
            for (row in 0 until rowsToCheck) {
                for (col in cols) {
                    val uIndex = row * rowStride + col * 2
                    val vuUIndex = row * rowStride + col * 2 + 1
                    if (uIndex < uBuffer.limit() && vuUIndex < vBuffer.limit()) {
                        samples++
                        if (uBuffer.get(uIndex) == vBuffer.get(vuUIndex)) matches++
                    }
                }
            }
            samples >= 3 && matches == samples
        } catch (_: Exception) {
            false
        }
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int
    ) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outputPos = outputOffset

        // The Y plane on most Camera2 devices is tightly packed. Bulk row copies remove
        // millions of ByteBuffer.get(index) calls per Full-HD frame and materially reduce
        // encoder latency.
        if (pixelStride == 1 && outputPixelStride == 1) {
            for (row in 0 until height) {
                val rowStart = row * rowStride
                buffer.position(rowStart)
                buffer.get(output, outputPos, width)
                outputPos += width
            }
            return
        }

        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                output[outputPos] = buffer.get(rowStart + col * pixelStride)
                outputPos += outputPixelStride
            }
        }
    }
}
