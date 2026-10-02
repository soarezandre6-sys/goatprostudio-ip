from pathlib import Path
import runpy

p = Path(__file__).resolve().parent / "build27_integrate.py"
s = p.read_text(encoding="utf-8")
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
if old not in s:
    raise RuntimeError("Trecho ambíguo da Build 27 não encontrado")
p.write_text(s.replace(old, new, 1), encoding="utf-8")
runpy.run_path(str(p), run_name="__main__")
