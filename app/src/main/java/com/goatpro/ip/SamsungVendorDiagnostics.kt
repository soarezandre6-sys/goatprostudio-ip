package com.goatpro.ip

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import android.util.Size

/**
 * Reads only metadata that Android exposes to third-party applications.
 * It does not use root or hidden/private APIs. The goal is to identify
 * Samsung/vendor Camera2 keys that may describe an OEM 8K recording path.
 */
class SamsungVendorDiagnostics(
    private val context: Context
) {
    data class Report(
        val summary: String,
        val fullText: String,
        val matchedKeyCount: Int,
        val vendorKeyCount: Int,
        val cameraCount: Int
    )

    private val interestingTerms = listOf(
        "samsung",
        "8k",
        "uhd",
        "4320",
        "video",
        "record",
        "remosaic",
        "sensor",
        "high",
        "resolution",
        "stream",
        "mode",
        "binning",
        "binned",
        "multi",
        "physical",
        "eis",
        "ois",
        "fps",
        "hevc",
        "hdr"
    )

    fun scan(): Report {
        val manager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val blocks = mutableListOf<String>()
        var matchedKeyCount = 0
        var vendorKeyCount = 0
        var cameraCount = 0

        val logicalIds = manager.cameraIdList.toList()
        val visited = linkedSetOf<String>()

        fun inspect(
            id: String,
            parentLogicalId: String? = null
        ) {
            if (!visited.add(id)) return

            val chars = runCatching {
                manager.getCameraCharacteristics(id)
            }.getOrNull() ?: return

            cameraCount++

            val facing = when (
                chars.get(CameraCharacteristics.LENS_FACING)
            ) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN"
            }

            val hardware = when (
                chars.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                )
            ) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ->
                    "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED ->
                    "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ->
                    "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 ->
                    "LEVEL_3"
                CameraCharacteristics
                    .INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL ->
                    "EXTERNAL"
                else -> "UNKNOWN"
            }

            val capabilities = chars.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ).orEmpty().joinToString(",")

            val physicalIds =
                if (Build.VERSION.SDK_INT >= 28) {
                    chars.physicalCameraIds.sorted()
                } else {
                    emptyList()
                }

            val characteristicKeys = chars.keys
            val requestKeys =
                chars.availableCaptureRequestKeys.orEmpty()
            val resultKeys =
                chars.availableCaptureResultKeys.orEmpty()
            val sessionKeys =
                if (Build.VERSION.SDK_INT >= 28) {
                    chars.availableSessionKeys.orEmpty()
                } else {
                    emptyList()
                }
            val physicalRequestKeys =
                if (Build.VERSION.SDK_INT >= 28) {
                    chars.availablePhysicalCameraRequestKeys.orEmpty()
                } else {
                    emptyList()
                }

            data class Row(
                val group: String,
                val name: String,
                val value: String?
            )

            val rows = mutableListOf<Row>()

            characteristicKeys.forEach { key ->
                val name = key.name
                if (isInteresting(name) || isVendorName(name)) {
                    if (isVendorName(name)) vendorKeyCount++
                    matchedKeyCount++
                    rows.add(
                        Row(
                            "CHAR",
                            name,
                            readCharacteristicValue(chars, key)
                        )
                    )
                }
            }

            requestKeys.forEach { key ->
                val name = key.name
                if (isInteresting(name) || isVendorName(name)) {
                    if (isVendorName(name)) vendorKeyCount++
                    matchedKeyCount++
                    rows.add(Row("REQUEST", name, null))
                }
            }

            sessionKeys.forEach { key ->
                val name = key.name
                if (isInteresting(name) || isVendorName(name)) {
                    if (isVendorName(name)) vendorKeyCount++
                    matchedKeyCount++
                    rows.add(Row("SESSION", name, null))
                }
            }

            physicalRequestKeys.forEach { key ->
                val name = key.name
                if (isInteresting(name) || isVendorName(name)) {
                    if (isVendorName(name)) vendorKeyCount++
                    matchedKeyCount++
                    rows.add(Row("PHYSICAL_REQUEST", name, null))
                }
            }

            resultKeys.forEach { key ->
                val name = key.name
                if (isInteresting(name) || isVendorName(name)) {
                    if (isVendorName(name)) vendorKeyCount++
                    matchedKeyCount++
                    rows.add(Row("RESULT", name, null))
                }
            }

            val pixelArray = chars.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )
            val activeArray = chars.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )
            val focal = chars.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.joinToString(",")

            val header = buildString {
                appendLine("CAMERA ID: " + id)
                appendLine(
                    "PARENT LOGICAL: " +
                        (parentLogicalId ?: "-")
                )
                appendLine("FACING: " + facing)
                appendLine("HARDWARE: " + hardware)
                appendLine("CAPABILITIES: " + capabilities)
                appendLine("PIXEL ARRAY: " + (pixelArray ?: "-"))
                appendLine("ACTIVE ARRAY: " + (activeArray ?: "-"))
                appendLine("FOCAL MM: " + (focal ?: "-"))
                appendLine(
                    "PHYSICAL IDS: " +
                        if (physicalIds.isEmpty()) {
                            "-"
                        } else {
                            physicalIds.joinToString(",")
                        }
                )
                appendLine(
                    "KEY COUNTS: char=" +
                        characteristicKeys.size +
                        " request=" + requestKeys.size +
                        " session=" + sessionKeys.size +
                        " physicalRequest=" +
                        physicalRequestKeys.size +
                        " result=" + resultKeys.size
                )
            }

            val body = if (rows.isEmpty()) {
                "MATCHED KEYS: none exposed"
            } else {
                rows
                    .distinctBy {
                        it.group + "|" + it.name
                    }
                    .sortedWith(
                        compareBy<Row>({ it.group }, { it.name })
                    )
                    .joinToString("\n") { row ->
                        buildString {
                            append("[")
                            append(row.group)
                            append("] ")
                            append(row.name)
                            if (row.value != null) {
                                append(" = ")
                                append(row.value)
                            }
                        }
                    }
            }

            blocks.add(header + body)

            physicalIds.forEach { physicalId ->
                inspect(
                    id = physicalId,
                    parentLogicalId = id
                )
            }
        }

        logicalIds.forEach { inspect(it) }

        val reportHeader = buildString {
            appendLine("GOAT CAM - SAMSUNG/VENDOR 8K DIAGNOSTIC")
            appendLine("Manufacturer: " + Build.MANUFACTURER)
            appendLine("Brand: " + Build.BRAND)
            appendLine("Model: " + Build.MODEL)
            appendLine("Device: " + Build.DEVICE)
            appendLine("Android SDK: " + Build.VERSION.SDK_INT)
            appendLine("Logical IDs: " + logicalIds.joinToString(","))
            appendLine("Inspected cameras: " + cameraCount)
            appendLine("Matched keys: " + matchedKeyCount)
            appendLine("Vendor-like keys: " + vendorKeyCount)
            appendLine(
                "NOTE: only metadata visible to ordinary third-party apps is listed."
            )
        }

        val fullText =
            reportHeader + "\n" +
                blocks.joinToString("\n\n----------------\n\n")

        val summary = when {
            vendorKeyCount > 0 ->
                "Diagnóstico Samsung: " +
                    vendorKeyCount +
                    " chave(s) vendor • " +
                    matchedKeyCount +
                    " candidata(s)"
            matchedKeyCount > 0 ->
                "Diagnóstico 8K: " +
                    matchedKeyCount +
                    " chave(s) relacionadas • vendor oculta/não nomeada"
            else ->
                "Diagnóstico 8K: nenhuma chave Samsung/vendor exposta"
        }

        return Report(
            summary = summary,
            fullText = fullText,
            matchedKeyCount = matchedKeyCount,
            vendorKeyCount = vendorKeyCount,
            cameraCount = cameraCount
        )
    }

    private fun isInteresting(name: String): Boolean {
        val normalized = name.lowercase()
        return interestingTerms.any { normalized.contains(it) }
    }

    private fun isVendorName(name: String): Boolean {
        val n = name.lowercase()
        return !n.startsWith("android.") ||
            n.contains("samsung") ||
            n.contains("sec.") ||
            n.contains("vendor")
    }

    @Suppress("UNCHECKED_CAST")
    private fun readCharacteristicValue(
        chars: CameraCharacteristics,
        key: CameraCharacteristics.Key<*>
    ): String? {
        return runCatching {
            val typed =
                key as CameraCharacteristics.Key<Any>
            stringify(chars.get(typed))
        }.getOrNull()
    }

    private fun stringify(value: Any?): String {
        if (value == null) return "null"

        return when (value) {
            is IntArray -> value.joinToString(
                prefix = "[",
                postfix = "]"
            )
            is LongArray -> value.joinToString(
                prefix = "[",
                postfix = "]"
            )
            is FloatArray -> value.joinToString(
                prefix = "[",
                postfix = "]"
            )
            is DoubleArray -> value.joinToString(
                prefix = "[",
                postfix = "]"
            )
            is BooleanArray -> value.joinToString(
                prefix = "[",
                postfix = "]"
            )
            is ByteArray -> {
                val preview = value
                    .take(32)
                    .joinToString(" ") {
                        "%02X".format(it)
                    }
                "bytes(" + value.size + ") " + preview +
                    if (value.size > 32) " …" else ""
            }
            is Array<*> -> value.joinToString(
                prefix = "[",
                postfix = "]"
            ) { stringify(it) }
            is Range<*> -> value.toString()
            is Size -> value.width.toString() +
                "x" + value.height.toString()
            else -> value.toString()
        }.take(2000)
    }
}
