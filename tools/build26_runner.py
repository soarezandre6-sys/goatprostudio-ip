from pathlib import Path
import runpy

p = Path(__file__).resolve().parent / "build26_integrate.py"
s = p.read_text(encoding="utf-8")
old = """# stopStreaming also stops low-overhead background path.
main = rep(
    main,
    '''        h264Encoder.stop()
        front4kDirectStreamer.stop()
        rtspServer.stop()
''',
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.stop()
        rtspServer.stop()
''',
    "stop streaming direct background",
)
"""
new = """# stopStreaming also stops low-overhead background path.
main = main.replace(
    '''        h264Encoder.stop()
        front4kDirectStreamer.stop()
        rtspServer.stop()
''',
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.stop()
        rtspServer.stop()
''',
    1,
)
"""
if old not in s:
    raise RuntimeError("Trecho do helper Build 26 não encontrado")
p.write_text(s.replace(old, new, 1), encoding="utf-8")
runpy.run_path(str(p), run_name="__main__")
