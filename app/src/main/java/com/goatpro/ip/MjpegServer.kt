package com.goatpro.ip

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
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
        fun onCameraControl(action: String, value: String?): String = "Comando não suportado"
        fun cameraControlStateJson(): String = "{\"available\":false}"
    }

    private data class AudioChunk(val sequence: Long, val bytes: ByteArray)

    private val running = AtomicBoolean(false)
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val latestAudio = AtomicReference<AudioChunk?>(null)
    private val audioSequence = AtomicLong(0L)
    private val audioEnabled = AtomicBoolean(false)
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

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled.set(enabled)
        if (!enabled) latestAudio.set(null)
    }

    fun isAudioEnabled(): Boolean = audioEnabled.get()

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
            socket.sendBufferSize = 128 * 1024
            socket.soTimeout = 10_000

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            when (path) {
                "/video", "/mjpeg" -> serveMjpeg(socket)
                "/audio", "/audio.pcm" -> serveAudioPcm(socket)
                "/snapshot.jpg" -> serveSnapshot(socket)
                "/health" -> serveHealth(socket)
                "/camera/state" -> serveCameraState(socket)
                "/camera/control" -> serveCameraControl(socket, query)
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
        // Avoid a large user-space queue. MJPEG should favor the newest frame over
        // buffering old frames when Wi-Fi throughput briefly drops.
        val out = socket.getOutputStream()
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
        if (!audioEnabled.get()) {
            serveText(socket, "503 Service Unavailable", "text/plain; charset=utf-8", "Microfone do GOAT PRO IP desligado")
            return
        }
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
        while (running.get() && audioEnabled.get() && !socket.isClosed) {
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
        val body = "{\"status\":\"ok\",\"app\":\"GOAT PRO IP\",\"version\":\"0.5.0-alpha\",\"videoClients\":${videoClients.size},\"streaming\":${running.get()},\"audioEnabled\":${audioEnabled.get()},\"audioRate\":${AudioCapture.SAMPLE_RATE},\"audioChannels\":1}"
        serveText(socket, "200 OK", "application/json", body)
    }

    private fun serveCameraState(socket: Socket) {
        val body = runCatching { listener?.cameraControlStateJson() ?: "{\"available\":false}" }
            .getOrElse { "{\"available\":false,\"error\":\"estado indisponível\"}" }
        serveText(socket, "200 OK", "application/json; charset=utf-8", body)
    }

    private fun serveCameraControl(socket: Socket, query: Map<String, String>) {
        val action = query["action"].orEmpty()
        if (action.isBlank()) {
            serveText(socket, "400 Bad Request", "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"ação ausente\"}")
            return
        }
        val message = runCatching { listener?.onCameraControl(action, query["value"]) ?: "Comando não suportado" }
            .getOrElse { "Falha ao aplicar controle" }
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        serveText(socket, "200 OK", "application/json; charset=utf-8", "{\"ok\":true,\"message\":\"$message\"}")
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            val key = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8") }.getOrDefault("")
            key to value
        }.toMap()
    }

    private fun serveHome(socket: Socket) {
        val html = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>GOAT PRO IP · Controles</title>
              <style>
                body{margin:0;background:#05070A;color:#fff;font-family:Arial,sans-serif}
                .wrap{max-width:980px;margin:auto;padding:18px}
                h1{color:#F2B620;margin:0 0 4px}
                .muted{color:#9ba3ad;font-size:13px}
                .grid{display:grid;grid-template-columns:minmax(280px,1.5fr) minmax(270px,1fr);gap:16px;margin-top:16px}
                .panel{background:#11161d;border:1px solid #2b323c;border-radius:12px;padding:14px}
                img{width:100%;height:auto;background:#000;border-radius:10px}
                label{display:block;color:#c9d0d8;font-size:12px;margin-top:12px}
                input[type=range]{width:100%}
                input[type=number]{width:100%;box-sizing:border-box;padding:9px;background:#080c11;color:#fff;border:1px solid #3a424d;border-radius:7px}
                button{padding:10px 12px;margin:5px 4px 5px 0;border:0;border-radius:8px;background:#F2B620;color:#050505;font-weight:bold;cursor:pointer}
                button.secondary{background:#28313c;color:#fff}
                .row{display:flex;gap:8px;flex-wrap:wrap}
                .value{color:#F2B620;font-weight:bold}
                #status{margin-top:10px;color:#9ee493}
                @media(max-width:760px){.grid{grid-template-columns:1fr}}
              </style>
            </head>
            <body>
              <div class="wrap">
                <h1>GOAT PRO IP</h1>
                <div class="muted">Painel web da câmera · controles aplicados no celular em tempo real</div>
                <div class="grid">
                  <div class="panel">
                    <img src="/video" alt="Live View">
                    <div id="status">Carregando estado da câmera…</div>
                  </div>
                  <div class="panel">
                    <div class="row">
                      <button onclick="cmd('switch')">Trocar câmera</button>
                      <button onclick="cmd('torch')">Lanterna</button>
                      <button class="secondary" onclick="cmd('focus')">Auto foco central</button>
                    </div>

                    <label>Zoom · <span class="value" id="zoomText">1.0×</span></label>
                    <input id="zoom" type="range" min="1" max="8" step="0.1" value="1" oninput="zoomText.textContent=this.value+'×'" onchange="cmd('zoom',this.value)">

                    <label>Compensação de exposição (EV) · <span class="value" id="evText">0</span></label>
                    <input id="ev" type="range" min="-4" max="4" step="1" value="0" oninput="evText.textContent=this.value" onchange="cmd('ev',this.value)">

                    <label><input id="manual" type="checkbox" onchange="cmd('manual',this.checked?'1':'0')"> Exposição manual (quando suportada)</label>

                    <label>ISO</label>
                    <input id="iso" type="number" value="100" onchange="cmd('iso',this.value)">

                    <label>Tempo de exposição · microssegundos (ex.: 10000 = 1/100 s)</label>
                    <input id="shutter" type="number" value="10000" onchange="cmd('shutterUs',this.value)">

                    <div class="row" style="margin-top:10px">
                      <button class="secondary" onclick="cmd('manual','0')">Voltar exposição automática</button>
                    </div>
                    <div class="muted" style="margin-top:8px">ISO e obturador manual só são habilitados se a câmera do aparelho anunciar suporte pelo Camera2.</div>
                  </div>
                </div>
              </div>
              <script>
                async function cmd(action,value){
                  try{
                    const q='/camera/control?action='+encodeURIComponent(action)+(value===undefined?'':'&value='+encodeURIComponent(value));
                    const r=await fetch(q,{cache:'no-store'});
                    const j=await r.json();
                    document.getElementById('status').textContent=j.message||'Controle aplicado';
                    setTimeout(loadState,120);
                  }catch(e){document.getElementById('status').textContent='Falha: '+e;}
                }
                async function loadState(){
                  try{
                    const s=await fetch('/camera/state?ts='+Date.now(),{cache:'no-store'}).then(r=>r.json());
                    if(!s.available){document.getElementById('status').textContent='Câmera indisponível';return;}
                    const z=document.getElementById('zoom');
                    z.min=s.minZoom;z.max=s.maxZoom;z.value=s.zoom;document.getElementById('zoomText').textContent=Number(s.zoom).toFixed(1)+'×';
                    const ev=document.getElementById('ev');
                    ev.min=s.minEv;ev.max=s.maxEv;ev.value=s.ev;document.getElementById('evText').textContent=s.ev;
                    document.getElementById('manual').checked=!!s.manual;
                    const iso=document.getElementById('iso');iso.min=s.minIso||50;iso.max=s.maxIso||12800;iso.value=s.iso||100;iso.disabled=!s.manualSupported;
                    const sh=document.getElementById('shutter');sh.min=s.minShutterUs||100;sh.max=s.maxShutterUs||1000000;sh.value=s.shutterUs||10000;sh.disabled=!s.manualSupported;
                    document.getElementById('manual').disabled=!s.manualSupported;
                    document.getElementById('status').textContent='Câmera '+s.camera+' · '+s.width+'×'+s.height+' · '+(s.torch?'lanterna ligada':'lanterna desligada');
                  }catch(e){document.getElementById('status').textContent='Estado indisponível: '+e;}
                }
                loadState();
                setInterval(loadState,3000);
              </script>
            </body>
            </html>
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
