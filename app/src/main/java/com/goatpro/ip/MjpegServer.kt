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
        val body = "{\"status\":\"ok\",\"app\":\"GOAT PRO IP\",\"version\":\"0.8.0-alpha\",\"videoClients\":${videoClients.size},\"streaming\":${running.get()},\"audioEnabled\":${audioEnabled.get()},\"audioRate\":${AudioCapture.SAMPLE_RATE},\"audioChannels\":1}"
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
                .rangeHead{display:flex;justify-content:space-between;gap:10px;align-items:center}
                .rangeLimits{color:#7f8995;font-size:11px;margin-top:2px}
                .rangeValue{color:#F2B620;font-weight:bold}
                input[type=number],select{width:100%;box-sizing:border-box;padding:9px;background:#080c11;color:#fff;border:1px solid #3a424d;border-radius:7px}
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
                    <div class="muted" style="color:#F2B620;font-weight:bold;margin-bottom:6px">TRANSMISSÃO / GOAT PRO STUDIO</div>

                    <label>Resolução</label>
                    <select id="resolution" onchange="cmd('resolution',this.value)">
                      <option value="HD">720p</option>
                      <option value="FHD">1080p</option>
                    </select>

                    <label>Preset rápido</label>
                    <select id="quality" onchange="cmd('quality',this.value)">
                      <option value="LOW_LATENCY">Baixa latência · Q50 · 20 FPS</option>
                      <option value="BALANCED">Equilibrado · Q65 · 20 FPS</option>
                      <option value="HIGH_QUALITY">Alta qualidade · Q80 · 20 FPS</option>
                      <option value="MAX_QUALITY">Máxima qualidade · Q90 · 15 FPS</option>
                      <option value="CUSTOM">Personalizado</option>
                    </select>

                    <label class="rangeHead">
                      <span>Qualidade JPEG</span>
                      <span class="rangeValue" id="jpegQualityText">65%</span>
                    </label>
                    <input id="jpegQuality" type="range" min="1" max="100" step="1" value="65"
                           oninput="jpegQualityText.textContent=this.value+'%'"
                           onchange="cmd('jpegQuality',this.value)">

                    <label class="rangeHead">
                      <span>Limite de FPS</span>
                      <span class="rangeValue" id="fpsLimitText">20 FPS</span>
                    </label>
                    <input id="fpsLimit" type="range" min="0" max="30" step="1" value="20"
                           oninput="fpsLimitText.textContent=(this.value==='0'?'Sem limite':this.value+' FPS')"
                           onchange="cmd('fpsLimit',this.value)">
                    <div class="rangeLimits">0 = sem limite · útil para medir o máximo real do aparelho</div>

                    <label>Rotação da imagem</label>
                    <select id="rotation" onchange="cmd('rotation',this.value)">
                      <option value="AUTO">Automático</option>
                      <option value="ROTATE_90">Girar 90° para direita</option>
                      <option value="ROTATE_180">Girar 180°</option>
                      <option value="ROTATE_270">Girar 270° / esquerda</option>
                    </select>

                    <label><input id="autoDiscovery" type="checkbox" onchange="cmd('autoDiscovery',this.checked?'1':'0')"> Conexão automática com o GOAT PRO Studio</label>
                    <label><input id="audioEnabled" type="checkbox" onchange="cmd('audio',this.checked?'1':'0')"> Áudio do celular</label>

                    <div class="muted" style="color:#F2B620;font-weight:bold;margin-top:16px;margin-bottom:6px">CONTROLES DA CÂMERA</div>
                    <div class="row">
                      <button onclick="cmd('switch')">Trocar câmera</button>
                      <button onclick="cmd('torch')">Lanterna</button>
                      <button class="secondary" onclick="cmd('focus')">Auto foco central</button>
                    </div>

                    <label>Zoom · <span class="value" id="zoomText">1.0×</span></label>
                    <input id="zoom" type="range" min="1" max="8" step="0.1" value="1" oninput="zoomText.textContent=this.value+'×'" onchange="cmd('zoom',this.value)">

                    <label>Compensação de exposição (EV) · <span class="value" id="evText">0</span></label>
                    <input id="ev" type="range" min="-4" max="4" step="1" value="0" oninput="evText.textContent=this.value" onchange="cmd('ev',this.value)">

                    <label><input id="manualFocus" type="checkbox" onchange="cmd('focusMode',this.checked?'manual':'auto')"> Foco manual</label>
                    <label class="rangeHead">
                      <span>Distância de foco</span>
                      <span class="rangeValue" id="focusText">Automático</span>
                    </label>
                    <input id="focusDistance" type="range" min="0" max="1000" step="1" value="0"
                           oninput="previewFocus(this.value)"
                           onchange="applyFocus(this.value)">
                    <div class="rangeLimits" id="focusLimits">Longe / ∞  ←→  Perto / macro</div>

                    <label><input id="manual" type="checkbox" onchange="cmd('manual',this.checked?'1':'0')"> Exposição manual (quando suportada)</label>

                    <label class="rangeHead">
                      <span>ISO</span>
                      <span class="rangeValue" id="isoText">ISO 100</span>
                    </label>
                    <input id="iso" type="range" min="50" max="12800" step="1" value="100"
                           oninput="isoText.textContent='ISO '+this.value"
                           onchange="cmd('iso',this.value)">
                    <div class="rangeLimits" id="isoLimits">Faixa da câmera: aguardando…</div>

                    <label class="rangeHead">
                      <span>Tempo de exposição</span>
                      <span class="rangeValue" id="shutterText">1/100 s</span>
                    </label>
                    <input id="shutter" type="range" min="0" max="1000" step="1" value="500"
                           oninput="previewShutter(this.value)"
                           onchange="applyShutter(this.value)">
                    <div class="rangeLimits" id="shutterLimits">Faixa da câmera: aguardando…</div>

                    <div class="row" style="margin-top:10px">
                      <button class="secondary" onclick="cmd('manual','0')">Voltar exposição automática</button>
                    </div>
                    <div class="muted" style="margin-top:8px">ISO e obturador manual só são habilitados se a câmera do aparelho anunciar suporte pelo Camera2.</div>
                  </div>
                </div>
              </div>
              <script>
                let shutterMinUs=100;
                let shutterMaxUs=1000000;

                function clamp(v,min,max){return Math.max(min,Math.min(max,v));}
                function sliderToShutterUs(pos){
                  const min=Math.max(1,Number(shutterMinUs)||1);
                  const max=Math.max(min,Number(shutterMaxUs)||min);
                  const t=clamp(Number(pos)/1000,0,1);
                  return Math.round(Math.exp(Math.log(min)+(Math.log(max)-Math.log(min))*t));
                }
                function shutterUsToSlider(us){
                  const min=Math.max(1,Number(shutterMinUs)||1);
                  const max=Math.max(min,Number(shutterMaxUs)||min);
                  const v=clamp(Number(us)||min,min,max);
                  if(max===min)return 0;
                  return Math.round(1000*(Math.log(v)-Math.log(min))/(Math.log(max)-Math.log(min)));
                }
                function formatShutter(us){
                  const v=Math.max(1,Number(us)||1);
                  if(v>=1000000)return (v/1000000).toFixed(v%1000000===0?0:2)+' s';
                  const denom=Math.round(1000000/v);
                  if(denom>=2)return '1/'+denom+' s';
                  if(v>=1000)return (v/1000).toFixed(1)+' ms';
                  return Math.round(v)+' µs';
                }
                let maxFocusDiopters=0;

                function sliderToFocusDiopters(pos){
                  const t=clamp(Number(pos)/1000,0,1);
                  return t*Math.max(0,Number(maxFocusDiopters)||0);
                }
                function focusDioptersToSlider(diopters){
                  const max=Math.max(0,Number(maxFocusDiopters)||0);
                  if(max<=0)return 0;
                  return Math.round(1000*clamp((Number(diopters)||0)/max,0,1));
                }
                function formatFocus(diopters){
                  const d=Math.max(0,Number(diopters)||0);
                  if(d<0.01)return '∞ / longe';
                  const meters=1/d;
                  if(meters>=10)return 'longe · '+meters.toFixed(0)+' m';
                  if(meters>=1)return meters.toFixed(1)+' m';
                  return Math.round(meters*100)+' cm';
                }
                function previewFocus(pos){
                  const d=sliderToFocusDiopters(pos);
                  document.getElementById('focusText').textContent=formatFocus(d);
                }
                async function applyFocus(pos){
                  const d=sliderToFocusDiopters(pos);
                  document.getElementById('manualFocus').checked=true;
                  document.getElementById('focusText').textContent=formatFocus(d);
                  await cmd('focusDistance',String(d));
                }

                function previewShutter(pos){
                  const us=sliderToShutterUs(pos);
                  document.getElementById('shutterText').textContent=formatShutter(us)+' · '+us+' µs';
                }
                async function applyShutter(pos){
                  const us=sliderToShutterUs(pos);
                  document.getElementById('shutterText').textContent=formatShutter(us)+' · '+us+' µs';
                  await cmd('shutterUs',String(us));
                }

                async function cmd(action,value){
                  try{
                    const q='/camera/control?action='+encodeURIComponent(action)+(value===undefined?'':'&value='+encodeURIComponent(value));
                    const r=await fetch(q,{cache:'no-store'});
                    const j=await r.json();
                    document.getElementById('status').textContent=j.message||'Controle aplicado';
                    if(action==='focusMode'){
                      document.getElementById('focusDistance').disabled=(value!=='manual');
                    }
                    setTimeout(loadState,120);
                  }catch(e){document.getElementById('status').textContent='Falha: '+e;}
                }
                async function loadState(){
                  try{
                    const s=await fetch('/camera/state?ts='+Date.now(),{cache:'no-store'}).then(r=>r.json());
                    if(!s.available){document.getElementById('status').textContent='Câmera indisponível';return;}
                    document.getElementById('resolution').value=s.resolution||'FHD';
                    document.getElementById('quality').value=s.quality||'BALANCED';
                    const jq=document.getElementById('jpegQuality');
                    jq.value=Number(s.jpegQuality||65);
                    document.getElementById('jpegQualityText').textContent=Math.round(jq.value)+'%';
                    const fl=document.getElementById('fpsLimit');
                    fl.value=Number(s.targetFps||0);
                    document.getElementById('fpsLimitText').textContent=
                      Number(s.targetFps||0)<=0?'Sem limite':Math.round(Number(s.targetFps))+' FPS';
                    document.getElementById('rotation').value=s.rotation||'AUTO';
                    document.getElementById('autoDiscovery').checked=!!s.autoDiscovery;
                    document.getElementById('audioEnabled').checked=!!s.audioEnabled;
                    const z=document.getElementById('zoom');
                    z.min=s.minZoom;z.max=s.maxZoom;z.value=s.zoom;document.getElementById('zoomText').textContent=Number(s.zoom).toFixed(1)+'×';
                    const ev=document.getElementById('ev');
                    ev.min=s.minEv;ev.max=s.maxEv;ev.value=s.ev;document.getElementById('evText').textContent=s.ev;

                    maxFocusDiopters=Math.max(0,Number(s.maxFocusDiopters||0));
                    const mf=document.getElementById('manualFocus');
                    const fd=document.getElementById('focusDistance');
                    mf.checked=!!s.manualFocus;
                    mf.disabled=!s.manualFocusSupported;
                    fd.disabled=!s.manualFocusSupported || !s.manualFocus;
                    fd.value=focusDioptersToSlider(Number(s.focusDiopters||0));
                    document.getElementById('focusText').textContent=
                      s.manualFocus ? formatFocus(Number(s.focusDiopters||0)) : 'Automático';
                    document.getElementById('focusLimits').textContent=
                      s.manualFocusSupported
                        ? 'Longe / ∞  ←→  Perto / macro · limite da lente: '+maxFocusDiopters.toFixed(2)+' D'
                        : 'Foco manual não suportado por esta câmera';

                    document.getElementById('manual').checked=!!s.manual;
                    const iso=document.getElementById('iso');
                    const minIso=Number(s.minIso||50), maxIso=Number(s.maxIso||12800), curIso=Number(s.iso||minIso);
                    iso.min=minIso;iso.max=maxIso;
                    iso.step=Math.max(1,Math.round((maxIso-minIso)/500));
                    iso.value=clamp(curIso,minIso,maxIso);
                    iso.disabled=!s.manualSupported;
                    document.getElementById('isoText').textContent='ISO '+Math.round(iso.value);
                    document.getElementById('isoLimits').textContent='Faixa da câmera: ISO '+minIso+' – '+maxIso;

                    shutterMinUs=Math.max(1,Number(s.minShutterUs||100));
                    shutterMaxUs=Math.max(shutterMinUs,Number(s.maxShutterUs||1000000));
                    const curShutter=clamp(Number(s.shutterUs||10000),shutterMinUs,shutterMaxUs);
                    const sh=document.getElementById('shutter');
                    sh.value=shutterUsToSlider(curShutter);
                    sh.disabled=!s.manualSupported;
                    document.getElementById('shutterText').textContent=formatShutter(curShutter)+' · '+Math.round(curShutter)+' µs';
                    document.getElementById('shutterLimits').textContent=
                      'Faixa da câmera: '+formatShutter(shutterMinUs)+' – '+formatShutter(shutterMaxUs);
                    document.getElementById('manual').disabled=!s.manualSupported;
                    document.getElementById('status').textContent=
                      'Câmera '+s.camera+' · '+s.width+'×'+s.height+
                      ' · JPEG '+(s.jpegQuality||'?')+'%'+
                      ' · '+(Number(s.targetFps||0)<=0?'FPS sem limite':'alvo '+s.targetFps+' FPS')+
                      ' · '+(s.torch?'lanterna ligada':'lanterna desligada')+
                      (s.manualApplied?' · EXPOSIÇÃO MANUAL ATIVA':'');
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

    fun videoClientCount(): Int = videoClients.size

    fun isRunning(): Boolean = running.get()
}
