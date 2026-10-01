package com.goatpro.ip

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(
    private val port: Int = 8080,
    private val listener: Listener? = null
) {
    interface Listener {
        fun onVideoClientCountChanged(count: Int)
    }

    private data class AudioChunk(val sequence: Long, val bytes: ByteArray)

    private val running = AtomicBoolean(false)
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val latestAudio = AtomicReference<AudioChunk?>(null)
    private val audioSequence = AtomicLong(0L)
    private val clients = CopyOnWriteArrayList<Socket>()
    private val videoClients = CopyOnWriteArrayList<Socket>()
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun offerFrame(jpeg: ByteArray) {
        latestFrame.set(jpeg)
    }

    fun offerAudio(pcm16le: ByteArray) {
        latestAudio.set(AudioChunk(audioSequence.incrementAndGet(), pcm16le))
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverThread = Thread {
            try {
                ServerSocket(port).also { serverSocket = it }.use { server ->
                    server.reuseAddress = true
                    while (running.get()) {
                        val socket = server.accept()
                        clients += socket
                        Thread { serve(socket) }.apply {
                            name = "goat-ip-client"
                            isDaemon = true
                            start()
                        }
                    }
                }
            } catch (_: Exception) {
                // stop() closes accept(); other failures end the server thread.
            } finally {
                running.set(false)
                closeAllClients()
            }
        }.apply {
            name = "goat-ip-server"
            isDaemon = true
            start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            when (path) {
                "/video", "/mjpeg" -> serveMjpeg(socket)
                "/audio", "/audio.pcm" -> serveAudioPcm(socket)
                "/snapshot.jpg" -> serveSnapshot(socket)
                "/health" -> serveHealth(socket)
                else -> serveHome(socket)
            }
        } catch (_: Exception) {
            // Browser/player disconnected or timed out.
        } finally {
            removeVideoClient(socket)
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun serveMjpeg(socket: Socket) {
        addVideoClient(socket)
        socket.soTimeout = 0
        val out = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").toByteArray()
        )
        out.flush()

        var lastFrame: ByteArray? = null
        while (running.get() && !socket.isClosed) {
            val frame = latestFrame.get()
            if (frame == null || frame === lastFrame) {
                Thread.sleep(5)
                continue
            }
            lastFrame = frame
            out.write("--frame\r\n".toByteArray())
            out.write("Content-Type: image/jpeg\r\n".toByteArray())
            out.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
            out.write(frame)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    private fun serveAudioPcm(socket: Socket) {
        socket.soTimeout = 0
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "Content-Type: audio/x-raw; format=S16LE; rate=${AudioCapture.SAMPLE_RATE}; channels=1\r\n\r\n").toByteArray()
        )
        out.flush()

        var lastSequence = -1L
        while (running.get() && !socket.isClosed) {
            val chunk = latestAudio.get()
            if (chunk == null || chunk.sequence == lastSequence) {
                Thread.sleep(5)
                continue
            }
            lastSequence = chunk.sequence
            out.write(chunk.bytes)
            out.flush()
        }
    }

    private fun serveSnapshot(socket: Socket) {
        val frame = latestFrame.get()
        if (frame == null) {
            serveText(socket, "503 Service Unavailable", "text/plain; charset=utf-8", "Aguardando primeiro quadro")
            return
        }
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.size}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(frame)
        out.flush()
    }

    private fun serveHealth(socket: Socket) {
        val body = "{\"status\":\"ok\",\"app\":\"GOAT PRO IP\",\"videoClients\":${videoClients.size}}"
        serveText(socket, "200 OK", "application/json", body)
    }

    private fun serveHome(socket: Socket) {
        val html = """
            <!doctype html>
            <html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>GOAT PRO IP</title></head>
            <body style="margin:0;background:#05070A;color:#fff;font-family:Arial;text-align:center">
            <div style="padding:24px"><h1 style="color:#F2B620">GOAT PRO IP</h1>
            <p>Transmissão local ativa.</p><img src="/video" style="max-width:100%;height:auto;border:2px solid #F2B620;border-radius:12px"></div>
            </body></html>
        """.trimIndent()
        serveText(socket, "200 OK", "text/html; charset=utf-8", html)
    }

    private fun serveText(socket: Socket, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(
            ("HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun addVideoClient(socket: Socket) {
        if (videoClients.addIfAbsent(socket)) {
            listener?.onVideoClientCountChanged(videoClients.size)
        }
    }

    private fun removeVideoClient(socket: Socket) {
        if (videoClients.remove(socket)) {
            listener?.onVideoClientCountChanged(videoClients.size)
        }
    }

    private fun closeAllClients() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        if (videoClients.isNotEmpty()) {
            videoClients.clear()
            listener?.onVideoClientCountChanged(0)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        closeAllClients()
        serverSocket = null
        latestFrame.set(null)
        latestAudio.set(null)
    }

    fun isRunning(): Boolean = running.get()
}
