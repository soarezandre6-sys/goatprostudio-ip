from pathlib import Path

p = Path('app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt')
s = p.read_text(encoding='utf-8')

old = '''                        val afModes = characteristics.get(
                            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                        ).orEmpty()
'''
new = '''                        val afModes = characteristics.get(
                            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                        ) ?: intArrayOf()
'''
if old not in s:
    raise RuntimeError('AF modes block not found')
s = s.replace(old, new, 1)

s = s.replace(
    'ranges: Array<Range<Int>>,\n            fps: Int',
    'ranges: Array<out Range<Int>>,\n            fps: Int',
    1
)

p.write_text(s, encoding='utf-8')
