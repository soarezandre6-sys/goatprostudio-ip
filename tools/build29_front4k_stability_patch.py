from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'pattern not found in {path}: {old[:80]!r}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

# 1) Keep MediaCodec drain independent from RTSP/network backpressure.
gpu = 'app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt'
replace_once(
    gpu,
    'import java.util.concurrent.Executor\nimport java.util.concurrent.atomic.AtomicBoolean\n',
    'import java.util.concurrent.ArrayBlockingQueue\nimport java.util.concurrent.Executor\nimport java.util.concurrent.TimeUnit\nimport java.util.concurrent.atomic.AtomicBoolean\n'
)
replace_once(
    gpu,
    '    private var workerThread: HandlerThread? = null\n    private var workerHandler: Handler? = null\n    private var drainThread: Thread? = null\n',
    '''    private var workerThread: HandlerThread? = null\n    private var workerHandler: Handler? = null\n    private var drainThread: Thread? = null\n    private var deliveryThread: Thread? = null\n\n    private data class EncodedUnit(\n        val data: ByteArray,\n        val presentationTimeUs: Long,\n        val keyFrame: Boolean,\n        val codecConfig: Boolean\n    )\n\n    private val deliveryQueue = ArrayBlockingQueue<EncodedUnit>(10)\n\n    @Volatile\n    private var droppedDeliveryFrames = 0L\n'''
)
replace_once(
    gpu,
    '        stopping.set(false)\n        firstEncodedFrame = false\n        config = request\n',
    '        stopping.set(false)\n        firstEncodedFrame = false\n        droppedDeliveryFrames = 0L\n        deliveryQueue.clear()\n        config = request\n'
)
replace_once(
    gpu,
    '        setupEncoder(outputWidth, outputHeight, actualFps, request.targetBitrate)\n        setupGl(probe.sourceSize, totalRotation)\n        startDrainThread()\n        openCamera(manager, request, characteristics)\n',
    '        setupEncoder(outputWidth, outputHeight, actualFps, request.targetBitrate)\n        setupGl(probe.sourceSize, totalRotation)\n        startDeliveryThread()\n        startDrainThread()\n        openCamera(manager, request, characteristics)\n'
)
replace_once(
    gpu,
    '''                                    ?.let {\n                                        listener.onAccessUnit(\n                                            it,\n                                            0L,\n                                            false,\n                                            true\n                                        )\n                                    }\n''',
    '''                                    ?.let {\n                                        enqueueAccessUnit(\n                                            it,\n                                            0L,\n                                            false,\n                                            true\n                                        )\n                                    }\n'''
)
replace_once(
    gpu,
    '''                                listener.onAccessUnit(\n                                    bytes,\n                                    info.presentationTimeUs,\n                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,\n                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0\n                                )\n''',
    '''                                enqueueAccessUnit(\n                                    bytes,\n                                    info.presentationTimeUs,\n                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,\n                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0\n                                )\n'''
)
replace_once(
    gpu,
    '    private fun fail(message: String) {\n',
    '''    private fun startDeliveryThread() {\n        deliveryThread = Thread {\n            while (\n                !stopping.get() &&\n                (starting.get() || running.get() || deliveryQueue.isNotEmpty())\n            ) {\n                val unit = try {\n                    deliveryQueue.poll(100, TimeUnit.MILLISECONDS)\n                } catch (_: InterruptedException) {\n                    null\n                } ?: continue\n\n                try {\n                    listener.onAccessUnit(\n                        unit.data,\n                        unit.presentationTimeUs,\n                        unit.keyFrame,\n                        unit.codecConfig\n                    )\n                } catch (_: Exception) {\n                    // Network/client delivery must never stall the encoder drain.\n                }\n            }\n        }.apply {\n            name = "goat-gpu-h264-delivery"\n            isDaemon = true\n            start()\n        }\n    }\n\n    private fun enqueueAccessUnit(\n        data: ByteArray,\n        presentationTimeUs: Long,\n        keyFrame: Boolean,\n        codecConfig: Boolean\n    ) {\n        val unit = EncodedUnit(\n            data = data,\n            presentationTimeUs = presentationTimeUs,\n            keyFrame = keyFrame,\n            codecConfig = codecConfig\n        )\n\n        if (deliveryQueue.offer(unit)) return\n\n        // Keep latency bounded. If the RTSP consumer/network is slower than the\n        // encoder, discard an old frame instead of blocking MediaCodec/GPU.\n        deliveryQueue.poll()\n        if (deliveryQueue.offer(unit)) {\n            droppedDeliveryFrames++\n            requestKeyFrame()\n        }\n    }\n\n    private fun fail(message: String) {\n'''
)
replace_once(
    gpu,
    '        runCatching { workerThread?.quitSafely() }\n        workerHandler = null\n        workerThread = null\n        drainThread = null\n        selectedSource = null\n',
    '''        runCatching { deliveryThread?.interrupt() }\n        deliveryThread = null\n        deliveryQueue.clear()\n        runCatching { workerThread?.quitSafely() }\n        workerHandler = null\n        workerThread = null\n        drainThread = null\n        selectedSource = null\n'''
)
replace_once(
    gpu,
    '''                val durationFps = if (duration > 0L) {\n                    (1_000_000_000L / duration).toInt().coerceAtLeast(1)\n                } else {\n                    120\n                }\n''',
    '''                val durationFps = if (duration > 0L) {\n                    (1_000_000_000L / duration).toInt().coerceAtLeast(1)\n                } else {\n                    // Zero means the HAL did not provide a useful timing value.\n                    // Never interpret that as 120 FPS for a high-resolution stream.\n                    30\n                }\n'''
)

# 2) Front 4K compatibility: use a video-shaped public source and sane bitrate.
front = 'app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt'
replace_once(front, '        val bitrate: Int = 36_000_000,\n', '        val bitrate: Int = 18_000_000,\n')
replace_once(
    front,
    '''        val safeBitrate = if (profile.bitrate > 0) {\n            profile.bitrate.coerceIn(12_000_000, 60_000_000)\n        } else if (profile.fps > 30) {\n            48_000_000\n        } else {\n            36_000_000\n        }\n''',
    '''        val safeBitrate = if (profile.bitrate > 0) {\n            profile.bitrate.coerceIn(8_000_000, 28_000_000)\n        } else if (profile.fps > 30) {\n            24_000_000\n        } else {\n            18_000_000\n        }\n'''
)
replace_once(
    front,
    '                preferLargestSource = true,\n                // profileFor() already requires a high-resolution public source.\n',
    '                // Prefer the public source closest to a video 16:9 stream instead\n                // of blindly selecting the largest 4:3 sensor output.\n                preferLargestSource = false,\n                // profileFor() already requires a high-resolution public source.\n'
)
replace_once(
    front,
    '                bitrate = if (maxFps > 30) 48_000_000 else 36_000_000,\n',
    '                bitrate = if (maxFps > 30) 24_000_000 else 18_000_000,\n'
)

# 3) MainActivity: bitrate follows actual requested FPS, not the camera capability ceiling.
main = 'app/src/main/java/com/goatpro/ip/MainActivity.kt'
replace_once(
    main,
    '''                    val profile = Front4kDirectStreamer.Profile(\n                        cameraId = cameraId,\n                        width = selectedResolution.size.width,\n                        height = selectedResolution.size.height,\n                        fps = (\n                            if (streamTargetFps > 0) streamTargetFps\n                            else DEFAULT_START_FPS\n                        ).coerceIn(5, 60),\n                        bitrate =\n                            if (streamBitrateBps > 0) {\n                                streamBitrateBps\n                            } else {\n                                selectedResolution.directBitrate\n                                    ?: 32_000_000\n                            },\n''',
    '''                    val front4kFps = (\n                        if (streamTargetFps > 0) streamTargetFps\n                        else DEFAULT_START_FPS\n                    ).coerceIn(5, 60)\n                    val front4kBitrate =\n                        if (streamBitrateBps > 0) {\n                            streamBitrateBps.coerceAtMost(28_000_000)\n                        } else {\n                            H264Encoder.recommendedBitrate(\n                                selectedResolution.size.width,\n                                selectedResolution.size.height,\n                                front4kFps,\n                                70\n                            )\n                        }\n                    val profile = Front4kDirectStreamer.Profile(\n                        cameraId = cameraId,\n                        width = selectedResolution.size.width,\n                        height = selectedResolution.size.height,\n                        fps = front4kFps,\n                        bitrate = front4kBitrate,\n'''
)

# 4) Larger TCP send buffer helps bursty 4K IDR frames.
rtsp = 'app/src/main/java/com/goatpro/ip/RtspH264Server.kt'
replace_once(
    rtsp,
    '                        runCatching { socket.sendBufferSize = 64 * 1024 }\n',
    '                        runCatching { socket.sendBufferSize = 512 * 1024 }\n'
)

print('Build 29 front 4K stability patch applied')
