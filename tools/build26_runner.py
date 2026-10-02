from pathlib import Path
import runpy

p = Path(__file__).resolve().parent / "build26_integrate.py"
s = p.read_text(encoding="utf-8")
old = '''# stopStreaming also stops low-overhead background path.\nmain = rep(\n    main,\n    '''        h264Encoder.stop()\n        front4kDirectStreamer.stop()\n        rtspServer.stop()\n''',\n    '''        h264Encoder.stop()\n        backgroundH264Streamer.stop()\n        backgroundDirectActive = false\n        front4kDirectStreamer.stop()\n        rtspServer.stop()\n''',\n    "stop streaming direct background",\n)\n'''
new = '''# stopStreaming also stops low-overhead background path.\nmain = main.replace(\n    '''        h264Encoder.stop()\n        front4kDirectStreamer.stop()\n        rtspServer.stop()\n''',\n    '''        h264Encoder.stop()\n        backgroundH264Streamer.stop()\n        backgroundDirectActive = false\n        front4kDirectStreamer.stop()\n        rtspServer.stop()\n''',\n    1,\n)\n'''
if old not in s:
    raise RuntimeError("Trecho do helper Build 26 não encontrado")
p.write_text(s.replace(old, new, 1), encoding="utf-8")
runpy.run_path(str(p), run_name="__main__")
