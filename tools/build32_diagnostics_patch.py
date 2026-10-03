from pathlib import Path

# Patch Camera1 GPU streamer with runtime health diagnostics and adaptive bitrate.
p = Path('app/src/main/java/com/goatpro/ip/Camera1GpuH264Streamer.kt')
s = p.read_text()

s = s.replace(
'''    @Volatile\n    private var firstEncodedFrame = false\n''',
'''    @Volatile\n    private var firstEncodedFrame = false\n\n    @Volatile\n    private var lastCameraFrameNs = 0L\n\n    @Volatile\n    private var lastEncodedFrameNs = 0L\n\n    @Volatile\n    private var lastDeliveryCompletedNs = 0L\n\n    @Volatile\n    private var droppedDeliveryFrames = 0L\n''')

s = s.replace(
'''        stopping.set(false)\n        firstEncodedFrame = false\n        deliveryQueue.clear()\n''',
'''        stopping.set(false)\n        firstEncodedFrame = false\n        lastCameraFrameNs = 0L\n        lastEncodedFrameNs = 0L\n        lastDeliveryCompletedNs = 0L\n        droppedDeliveryFrames = 0L\n        deliveryQueue.clear()\n''')

s = s.replace(
'''        handler.postDelayed({\n            if ((starting.get() || running.get()) && !firstEncodedFrame) {\n                fail("Camera1 GPU abriu, mas não entregou H.264 em 5 segundos.")\n            }\n        }, 5_000L)\n        return true\n''',
'''        handler.postDelayed({\n            if ((starting.get() || running.get()) && !firstEncodedFrame) {\n                fail("DIAG INICIAL: Camera1/GPU abriu, mas não entregou H.264 em 5 segundos.")\n            }\n        }, 5_000L)\n\n        Thread {\n            while (!stopping.get() && (starting.get() || running.get())) {\n                try {\n                    Thread.sleep(1_000L)\n                } catch (_: InterruptedException) {\n                    break\n                }\n                if (!firstEncodedFrame) continue\n                val now = System.nanoTime()\n                val cameraAgeMs = if (lastCameraFrameNs > 0L)\n                    (now - lastCameraFrameNs) / 1_000_000L else Long.MAX_VALUE\n                val encoderAgeMs = if (lastEncodedFrameNs > 0L)\n                    (now - lastEncodedFrameNs) / 1_000_000L else Long.MAX_VALUE\n                val deliveryAgeMs = if (lastDeliveryCompletedNs > 0L)\n                    (now - lastDeliveryCompletedNs) / 1_000_000L else Long.MAX_VALUE\n\n                when {\n                    cameraAgeMs > 3_000L -> {\n                        fail("DIAG CAMERA: a frontal parou de entregar frames por ${cameraAgeMs} ms.")\n                        break\n                    }\n                    encoderAgeMs > 3_000L -> {\n                        fail("DIAG ENCODER: a câmera continua ativa, mas o H.264 parou por ${encoderAgeMs} ms.")\n                        break\n                    }\n                    deliveryQueue.isNotEmpty() && deliveryAgeMs > 3_000L -> {\n                        fail("DIAG RTSP/REDE: o H.264 continua sendo gerado, mas a entrega ao Studio travou por ${deliveryAgeMs} ms.")\n                        break\n                    }\n                }\n            }\n        }.apply {\n            name = "goat-camera1-gpu-health"\n            isDaemon = true\n            start()\n        }\n        return true\n''')

s = s.replace(
'''        try {\n            texture.updateTexImage()\n            texture.getTransformMatrix(textureTransform)\n''',
'''        try {\n            texture.updateTexImage()\n            lastCameraFrameNs = System.nanoTime()\n            texture.getTransformMatrix(textureTransform)\n''')

s = s.replace(
'''                                val bytes = ByteArray(info.size)\n                                out.get(bytes)\n\n                                if (!firstEncodedFrame) {\n''',
'''                                val bytes = ByteArray(info.size)\n                                out.get(bytes)\n                                lastEncodedFrameNs = System.nanoTime()\n\n                                if (!firstEncodedFrame) {\n                                    lastDeliveryCompletedNs = lastEncodedFrameNs\n''')

s = s.replace(
'''                    listener.onAccessUnit(\n                        unit.data,\n                        unit.ptsUs,\n                        unit.keyFrame,\n                        unit.codecConfig\n                    )\n                } catch (_: Exception) {\n''',
'''                    listener.onAccessUnit(\n                        unit.data,\n                        unit.ptsUs,\n                        unit.keyFrame,\n                        unit.codecConfig\n                    )\n                    lastDeliveryCompletedNs = System.nanoTime()\n                } catch (_: Exception) {\n''')

s = s.replace(
'''    private fun enqueue(unit: EncodedUnit) {\n        if (deliveryQueue.offer(unit)) return\n        deliveryQueue.poll()\n        if (deliveryQueue.offer(unit)) {\n            requestKeyFrame()\n        }\n    }\n''',
'''    private fun enqueue(unit: EncodedUnit) {\n        if (deliveryQueue.offer(unit)) return\n        deliveryQueue.poll()\n        droppedDeliveryFrames++\n        if (deliveryQueue.offer(unit)) {\n            requestKeyFrame()\n        }\n\n        // If the network is briefly slower than 4K, reduce bitrate without\n        // touching resolution or FPS. This keeps latency bounded instead of\n        // allowing a backlog to freeze the visible stream.\n        if (droppedDeliveryFrames > 0L && droppedDeliveryFrames % 12L == 0L) {\n            val newBitrate = (actualBitrate * 0.82).toInt().coerceAtLeast(10_000_000)\n            if (newBitrate < actualBitrate) {\n                actualBitrate = newBitrate\n                runCatching {\n                    codec?.setParameters(\n                        Bundle().apply {\n                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)\n                        }\n                    )\n                }\n                requestKeyFrame()\n            }\n        }\n    }\n''')

p.write_text(s)

# Patch RTSP server: never let a slow TCP/UDP client block the camera/encoder path.
p = Path('app/src/main/java/com/goatpro/ip/RtspH264Server.kt')
s = p.read_text()

s = s.replace(
'''import java.util.concurrent.CopyOnWriteArrayList\nimport java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicInteger\n''',
'''import java.util.concurrent.ArrayBlockingQueue\nimport java.util.concurrent.CopyOnWriteArrayList\nimport java.util.concurrent.TimeUnit\nimport java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicInteger\n''')

s = s.replace(
'''    private enum class TransportMode { NONE, UDP, TCP }\n\n    private class ClientSession(\n''',
'''    private enum class TransportMode { NONE, UDP, TCP }\n\n    private data class PendingAccessUnit(\n        val nals: List<ByteArray>,\n        val timestamp: Long,\n        val keyFrame: Boolean\n    )\n\n    private class ClientSession(\n''')

s = s.replace(
'''        val writeLock = Any()\n\n        @Volatile\n        var playing = false\n''',
'''        val writeLock = Any()\n        val sendQueue = ArrayBlockingQueue<PendingAccessUnit>(4)\n        val senderRunning = AtomicBoolean(false)\n\n        @Volatile\n        var senderThread: Thread? = null\n\n        @Volatile\n        var waitingForKeyFrame = true\n\n        @Volatile\n        var queueOverflows = 0\n\n        @Volatile\n        var playing = false\n''')

s = s.replace(
'''        fun close() {\n            playing = false\n            runCatching { udpRtpSocket?.close() }\n            runCatching { udpRtcpSocket?.close() }\n            runCatching { socket.close() }\n        }\n''',
'''        fun close() {\n            playing = false\n            senderRunning.set(false)\n            runCatching { senderThread?.interrupt() }\n            senderThread = null\n            sendQueue.clear()\n            runCatching { udpRtpSocket?.close() }\n            runCatching { udpRtcpSocket?.close() }\n            runCatching { socket.close() }\n        }\n''')

old = '''        val timestamp = ((presentationTimeUs * 90L) / 1000L) and 0xffffffffL\n        val targets = clients.filter { it.playing && it.transportMode != TransportMode.NONE }\n        if (targets.isEmpty()) return\n\n        targets.forEach { client ->\n            try {\n                sendNals.forEachIndexed { index, nal ->\n                    val marker = index == sendNals.lastIndex\n                    sendNal(client, nal, timestamp, marker)\n                }\n                if (client.transportMode == TransportMode.TCP) {\n                    synchronized(client.writeLock) {\n                        client.output.flush()\n                    }\n                }\n            } catch (_: Exception) {\n                removeClient(client)\n            }\n        }\n    }\n'''
new = '''        val timestamp = ((presentationTimeUs * 90L) / 1000L) and 0xffffffffL\n        val targets = clients.filter { it.playing && it.transportMode != TransportMode.NONE }\n        if (targets.isEmpty()) return\n\n        val pending = PendingAccessUnit(\n            nals = sendNals.map { it.copyOf() },\n            timestamp = timestamp,\n            keyFrame = keyFrame\n        )\n        targets.forEach { client ->\n            enqueueClientAccessUnit(client, pending)\n        }\n    }\n\n    private fun enqueueClientAccessUnit(client: ClientSession, unit: PendingAccessUnit) {\n        if (!client.playing || client.transportMode == TransportMode.NONE) return\n\n        if (client.waitingForKeyFrame && !unit.keyFrame) return\n        if (unit.keyFrame) client.waitingForKeyFrame = false\n\n        if (!client.sendQueue.offer(unit)) {\n            client.sendQueue.clear()\n            client.queueOverflows++\n            client.waitingForKeyFrame = true\n\n            if (unit.keyFrame) {\n                client.waitingForKeyFrame = false\n                client.sendQueue.offer(unit)\n            }\n\n            // A socket that blocks repeatedly is worse than a reconnect. Closing\n            // it forces the Studio to reconnect instead of leaving a frozen 4K frame.\n            if (client.queueOverflows >= 3) {\n                removeClient(client)\n                return\n            }\n        } else if (client.queueOverflows > 0) {\n            client.queueOverflows--\n        }\n\n        startClientSender(client)\n    }\n\n    private fun startClientSender(client: ClientSession) {\n        if (!client.senderRunning.compareAndSet(false, true)) return\n        client.senderThread = Thread {\n            try {\n                while (\n                    running.get() &&\n                    client.senderRunning.get() &&\n                    client.playing &&\n                    !client.socket.isClosed\n                ) {\n                    val unit = try {\n                        client.sendQueue.poll(250, TimeUnit.MILLISECONDS)\n                    } catch (_: InterruptedException) {\n                        null\n                    } ?: continue\n\n                    unit.nals.forEachIndexed { index, nal ->\n                        sendNal(\n                            client,\n                            nal,\n                            unit.timestamp,\n                            index == unit.nals.lastIndex\n                        )\n                    }\n                    if (client.transportMode == TransportMode.TCP) {\n                        synchronized(client.writeLock) {\n                            client.output.flush()\n                        }\n                    }\n                }\n            } catch (_: Exception) {\n                removeClient(client)\n            } finally {\n                client.senderRunning.set(false)\n            }\n        }.apply {\n            name = "goat-rtsp-sender"\n            isDaemon = true\n            start()\n        }\n    }\n'''
if old not in s:
    raise SystemExit('RTSP onAccessUnit block not found')
s = s.replace(old, new)

s = s.replace(
'''                        if (!session.playing) {\n                            session.playing = true\n                            val count = activeCount.incrementAndGet()\n''',
'''                        if (!session.playing) {\n                            session.playing = true\n                            session.waitingForKeyFrame = true\n                            session.queueOverflows = 0\n                            session.sendQueue.clear()\n                            val count = activeCount.incrementAndGet()\n''')

p.write_text(s)
