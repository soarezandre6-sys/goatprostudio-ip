from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt"
text = path.read_text(encoding="utf-8")
replacements = [
    (
        '''            val nrModes = characteristics.get(\n                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES\n            ).orEmpty()\n''',
        '''            val nrModes = characteristics.get(\n                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES\n            ) ?: intArrayOf()\n''',
    ),
    (
        '''            val edgeModes = characteristics.get(\n                CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES\n            ).orEmpty()\n''',
        '''            val edgeModes = characteristics.get(\n                CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES\n            ) ?: intArrayOf()\n''',
    ),
    (
        '''            val effectModes = characteristics.get(\n                CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS\n            ).orEmpty()\n''',
        '''            val effectModes = characteristics.get(\n                CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS\n            ) ?: intArrayOf()\n''',
    ),
]
changed = False
for old, new in replacements:
    if old in text:
        text = text.replace(old, new, 1)
        changed = True
if not changed and '.orEmpty()' in '\n'.join(text.splitlines()[630:680]):
    raise SystemExit('Build41 compile fix: targeted orEmpty calls not matched')
path.write_text(text, encoding="utf-8")
print('Build 41 Kotlin compile fix applied')
