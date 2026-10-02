from pathlib import Path
import runpy

p = Path(__file__).resolve().parent / "build27_integrate.py"
s = p.read_text(encoding="utf-8")

# 1) Tornar único o ponto de inserção da rota high-speed em startStreaming().
old = """main = once(
    main,
    '''        if (selectedResolution.directFront4k) {\n''',
    '''        val selectedCamera = selectedCameraOption
        val highSpeedRequested =
            !selectedResolution.directFront4k &&
            streamTargetFps > 60 &&
            selectedCamera != null &&
            HighSpeedH264Streamer.supports(
                this,
                selectedCamera.logicalCameraId,
                selectedResolution.size,
                streamTargetFps
            )

        if (selectedResolution.directFront4k) {\n''',
    \"highspeed request flag\",
)
"""
new = """main = once(
    main,
    '''        if (selectedResolution.directFront4k) {
            h264Encoder.stop()
            rtspServer.start()
''',
    '''        val selectedCamera = selectedCameraOption
        val highSpeedRequested =
            !selectedResolution.directFront4k &&
            streamTargetFps > 60 &&
            selectedCamera != null &&
            HighSpeedH264Streamer.supports(
                this,
                selectedCamera.logicalCameraId,
                selectedResolution.size,
                streamTargetFps
            )

        if (selectedResolution.directFront4k) {
            h264Encoder.stop()
            rtspServer.start()
''',
    \"highspeed request flag\",
)
"""
if old in s:
    s = s.replace(old, new, 1)

# 2) stopStreaming() e onDestroy() possuem o mesmo par de linhas: substituir os dois de uma vez.
old_stop = """# Stop/destroy/active state include high-speed route.
main = once(
    main,
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
''',
    '''        h264Encoder.stop()
        highSpeedH264Streamer.stop()
        backgroundH264Streamer.stop()
''',
    \"stop highspeed\",
)
# onDestroy has the same pair later; replace the next occurrence.
main = once(
    main,
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
''',
    '''        h264Encoder.stop()
        highSpeedH264Streamer.stop()
        backgroundH264Streamer.stop()
''',
    \"destroy highspeed\",
)
"""
new_stop = """# Stop/destroy/active state include high-speed route.
main = main.replace(
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
''',
    '''        h264Encoder.stop()
        highSpeedH264Streamer.stop()
        backgroundH264Streamer.stop()
''',
    2,
)
"""
if old_stop in s:
    s = s.replace(old_stop, new_stop, 1)

# 3) Tornar única a alteração de isStreamingActive().
old_active = """main = once(
    main,
    '''            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
''',
    '''            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting() ||
            highSpeedH264Streamer.isRunning() ||
            highSpeedH264Streamer.isStarting()
''',
    \"active highspeed\",
)
"""
new_active = """main = once(
    main,
    '''    private fun isStreamingActive(): Boolean =
        server.isRunning() ||
            rtspServer.isRunning() ||
            front4kDirectStreamer.isRunning() ||
            front4kDirectStreamer.isStarting() ||
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
''',
    '''    private fun isStreamingActive(): Boolean =
        server.isRunning() ||
            rtspServer.isRunning() ||
            front4kDirectStreamer.isRunning() ||
            front4kDirectStreamer.isStarting() ||
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting() ||
            highSpeedH264Streamer.isRunning() ||
            highSpeedH264Streamer.isStarting()
''',
    \"active highspeed\",
)
"""
if old_active in s:
    s = s.replace(old_active, new_active, 1)

p.write_text(s, encoding="utf-8")
runpy.run_path(str(p), run_name="__main__")
