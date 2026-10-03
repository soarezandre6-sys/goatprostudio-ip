from pathlib import Path

main_path = Path('app/src/main/java/com/goatpro/ip/MainActivity.kt')
server_path = Path('app/src/main/java/com/goatpro/ip/MjpegServer.kt')

main = main_path.read_text(encoding='utf-8')
server = server_path.read_text(encoding='utf-8')

old_handler = '''            "bitrateKbps" -> {
                val requested = value?.toIntOrNull() ?: return
                streamBitrateBps = if (requested <= 0) {
                    0
                } else {
                    requested.coerceIn(500, 60_000) * 1_000
                }
                h264Encoder.stop()
                if (front4kDirectStreamer.isRunning()) {
                    autoRestartStreamAfterCameraBind = true
                    stopStreaming()
                    startCamera()
                }
                updateStreamInfo()
            }
'''
new_handler = '''            "bitrateKbps" -> {
                val requested = value?.toIntOrNull() ?: return
                val bitrateKbps = when {
                    requested <= 0 -> 0
                    selectedResolution.directFront4k -> requested.coerceIn(8_000, 18_000)
                    else -> requested.coerceIn(500, 60_000)
                }
                streamBitrateBps = bitrateKbps * 1_000

                // Build 45: changing the 4K frontal bitrate must not tear down
                // MediaRecorder/camera while the live stream is running. The native
                // Camera1 + MediaRecorder route has no safe public live bitrate API,
                // so the new value is stored and applied on the next stream start.
                if (!selectedResolution.directFront4k) {
                    h264Encoder.stop()
                }
                updateStreamInfo()
                saveSmartLinkState()
            }
'''
if old_handler not in main:
    raise SystemExit('MainActivity bitrate handler anchor not found')
main = main.replace(old_handler, new_handler, 1)

old_json = ''',\\\"bitrateKbps\\\":${streamBitrateBps / 1000}" +
                ",\\\"watermarkEnabled\\\":$watermarkEnabled" +'''
new_json = ''',\\\"bitrateKbps\\\":${streamBitrateBps / 1000}" +
                ",\\\"bitrateMinKbps\\\":${if (selectedResolution.directFront4k) 8_000 else 500}" +
                ",\\\"bitrateMaxKbps\\\":${if (selectedResolution.directFront4k) 18_000 else 60_000}" +
                ",\\\"bitrateAppliesOnRestart\\\":${selectedResolution.directFront4k}" +
                ",\\\"watermarkEnabled\\\":$watermarkEnabled" +'''
if old_json not in main:
    raise SystemExit('MainActivity bitrate JSON anchor not found')
main = main.replace(old_json, new_json, 1)

old_slider = '''                    <input id="bitrateKbps" type="range" min="0" max="60000" step="500" value="0"
                           oninput="bitrateText.textContent=(this.value==='0'?'Automático':(Number(this.value)/1000).toFixed(1)+' Mbps')"
                           onchange="cmd('bitrateKbps',this.value)">
                    <div class="rangeLimits">0 = automático · no 4K frontal é aplicado ao reiniciar o stream</div>
'''
new_slider = '''                    <input id="bitrateKbps" type="range" min="0" max="60000" step="500" value="0" data-manual-min="500"
                           oninput="bitrateText.textContent=(this.value==='0'?'Automático':(Number(this.value)/1000).toFixed(1)+' Mbps')"
                           onchange="applyBitrate(this)">
                    <div class="rangeLimits" id="bitrateLimits">0 = automático</div>
'''
if old_slider not in server:
    raise SystemExit('MjpegServer bitrate slider anchor not found')
server = server.replace(old_slider, new_slider, 1)

old_cmd_anchor = '''                async function cmd(action,value){
'''
new_cmd_anchor = '''                function applyBitrate(el){
                  let v=Number(el.value||0);
                  const manualMin=Number(el.dataset.manualMin||500);
                  const manualMax=Number(el.max||60000);
                  if(v>0){
                    v=Math.max(manualMin,Math.min(manualMax,v));
                    el.value=String(v);
                    document.getElementById('bitrateText').textContent=(v/1000).toFixed(1)+' Mbps';
                  }
                  cmd('bitrateKbps',String(v));
                }

                async function cmd(action,value){
'''
if old_cmd_anchor not in server:
    raise SystemExit('MjpegServer cmd anchor not found')
server = server.replace(old_cmd_anchor, new_cmd_anchor, 1)

old_state = '''                    const br=document.getElementById('bitrateKbps');
                    br.value=Number(s.bitrateKbps||0);
                    document.getElementById('bitrateText').textContent=
                      Number(s.bitrateKbps||0)<=0?'Automático':(Number(s.bitrateKbps)/1000).toFixed(1)+' Mbps';
'''
new_state = '''                    const br=document.getElementById('bitrateKbps');
                    const brMin=Number(s.bitrateMinKbps||500);
                    const brMax=Number(s.bitrateMaxKbps||60000);
                    br.dataset.manualMin=String(brMin);
                    br.max=String(brMax);
                    let brValue=Number(s.bitrateKbps||0);
                    if(brValue>0) brValue=Math.max(brMin,Math.min(brMax,brValue));
                    br.value=brValue;
                    document.getElementById('bitrateText').textContent=
                      brValue<=0?'Automático':(brValue/1000).toFixed(1)+' Mbps';
                    document.getElementById('bitrateLimits').textContent=
                      s.bitrateAppliesOnRestart
                        ? 'Automático · 4K frontal manual: '+(brMin/1000).toFixed(0)+'–'+(brMax/1000).toFixed(0)+' Mbps · aplica ao parar/iniciar a transmissão'
                        : 'Automático · manual: '+(brMin/1000).toFixed(1)+'–'+(brMax/1000).toFixed(1)+' Mbps';
'''
if old_state not in server:
    raise SystemExit('MjpegServer bitrate state anchor not found')
server = server.replace(old_state, new_state, 1)

main_path.write_text(main, encoding='utf-8')
server_path.write_text(server, encoding='utf-8')
print('Build 45 bitrate fix applied')
