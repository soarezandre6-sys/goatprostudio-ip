from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GPU = ROOT / "app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Build39 patch: trecho nao encontrado: {label}")
    return text.replace(old, new, 1)


gpu = GPU.read_text(encoding="utf-8")

# Build 39: never queue multiple 4K JPEG captures. Build 38 fired on a fixed timer,
# so a JPEG slower than the timer accumulated requests and eventually stalled the session.
gpu = replace_once(
    gpu,
    """    @Volatile
    private var mjpegFps = 5

    private var mjpegSize = Size(0, 0)
    private var mjpegOrientationDegrees = 0

    private val mjpegCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mjpegEnabled || stopping.get()) return
            captureMjpegFrame()
            workerHandler?.postDelayed(
                this,
                1000L / mjpegFps.coerceIn(1, MAX_GPU_MJPEG_FPS)
            )
        }
    }
""",
    """    @Volatile
    private var mjpegFps = 10

    private val mjpegCaptureInFlight = AtomicBoolean(false)

    @Volatile
    private var mjpegLastCaptureStartedNs = 0L

    private var mjpegSize = Size(0, 0)
    private var mjpegOrientationDegrees = 0

    private val mjpegCaptureRunnable = object : Runnable {
        override fun run() {
            if (!mjpegEnabled || stopping.get()) return
            if (!mjpegCaptureInFlight.compareAndSet(false, true)) return

            mjpegLastCaptureStartedNs = System.nanoTime()
            if (!captureMjpegFrame()) {
                mjpegCaptureInFlight.set(false)
                scheduleNextMjpegCapture(80L)
                return
            }

            // Safety watchdog. Normal pacing is driven by ImageReader completion;
            // this only recovers a device that accepted capture() but never returned JPEG.
            workerHandler?.postDelayed({
                if (mjpegCaptureInFlight.compareAndSet(true, false)) {
                    scheduleNextMjpegCapture(100L)
                }
            }, MJPEG_CAPTURE_TIMEOUT_MS)
        }
    }
""",
    "mjpeg flow state",
)

# Higher-quality JPEG, but bounded so 4K frames do not explode in size/bandwidth.
gpu = replace_once(
    gpu,
    """    fun setMjpegOutput(enabled: Boolean, quality: Int = 50, fps: Int = 5) {
        mjpegEnabled = enabled
        mjpegQuality = quality.coerceIn(20, 90)
        mjpegFps = fps.coerceIn(1, MAX_GPU_MJPEG_FPS)
        workerHandler?.post { updateMjpegCaptureLoop() }
    }
""",
    """    fun setMjpegOutput(enabled: Boolean, quality: Int = 88, fps: Int = 10) {
        mjpegEnabled = enabled
        mjpegQuality = quality.coerceIn(MIN_GPU_MJPEG_QUALITY, MAX_GPU_MJPEG_QUALITY)
        mjpegFps = fps.coerceIn(1, MAX_GPU_MJPEG_FPS)
        workerHandler?.post { updateMjpegCaptureLoop() }
    }
""",
    "mjpeg public settings",
)

# Completion of the hardware JPEG drives the next capture. There can only be one
# frame in flight, so slow Samsung JPEG processing cannot create a growing backlog.
gpu = replace_once(
    gpu,
    """                } finally {
                    runCatching { image.close() }
                }
            }
        }, handler)
""",
    """                } finally {
                    runCatching { image.close() }
                    mjpegCaptureInFlight.set(false)
                    if (mjpegEnabled && !stopping.get()) {
                        val targetIntervalMs =
                            1000L / mjpegFps.coerceIn(1, MAX_GPU_MJPEG_FPS)
                        val elapsedMs = if (mjpegLastCaptureStartedNs > 0L) {
                            ((System.nanoTime() - mjpegLastCaptureStartedNs) / 1_000_000L)
                                .coerceAtLeast(0L)
                        } else {
                            targetIntervalMs
                        }
                        scheduleNextMjpegCapture(
                            (targetIntervalMs - elapsedMs).coerceAtLeast(0L)
                        )
                    }
                }
            }
        }, handler)
""",
    "mjpeg completion pacing",
)

# Reset the in-flight gate when enabling/disabling and provide one central scheduler.
gpu = replace_once(
    gpu,
    """    private fun updateMjpegCaptureLoop() {
        val handler = workerHandler ?: return
        handler.removeCallbacks(mjpegCaptureRunnable)
        if (
            mjpegEnabled &&
            !stopping.get() &&
            captureSession != null &&
            cameraDevice != null &&
            mjpegReader != null
        ) {
            handler.post(mjpegCaptureRunnable)
        }
    }

    private fun captureMjpegFrame() {
""",
    """    private fun updateMjpegCaptureLoop() {
        val handler = workerHandler ?: return
        handler.removeCallbacks(mjpegCaptureRunnable)
        if (!mjpegEnabled || stopping.get()) {
            mjpegCaptureInFlight.set(false)
            return
        }
        if (
            captureSession != null &&
            cameraDevice != null &&
            mjpegReader != null &&
            !mjpegCaptureInFlight.get()
        ) {
            handler.post(mjpegCaptureRunnable)
        }
    }

    private fun scheduleNextMjpegCapture(delayMs: Long) {
        val handler = workerHandler ?: return
        handler.removeCallbacks(mjpegCaptureRunnable)
        if (
            mjpegEnabled &&
            !stopping.get() &&
            captureSession != null &&
            cameraDevice != null &&
            mjpegReader != null
        ) {
            handler.postDelayed(mjpegCaptureRunnable, delayMs.coerceAtLeast(0L))
        }
    }

    private fun captureMjpegFrame(): Boolean {
""",
    "mjpeg scheduler",
)

# Return whether Camera2 accepted the capture request so the gate never remains stuck
# after an immediate camera exception.
gpu = replace_once(
    gpu,
    """            session.capture(builder.build(), null, handler)
        } catch (_: Exception) {
            // Keep H.264 alive even if this device refuses a video snapshot.
        }
    }
""",
    """            session.capture(builder.build(), null, handler)
            return true
        } catch (_: Exception) {
            // Keep H.264 alive even if this device refuses a video snapshot.
            return false
        }
    }
""",
    "mjpeg capture boolean",
)

# Make release deterministic and remove a stale in-flight state across camera switches.
gpu = replace_once(
    gpu,
    """        mjpegEnabled = false
        workerHandler?.removeCallbacks(mjpegCaptureRunnable)
""",
    """        mjpegEnabled = false
        mjpegCaptureInFlight.set(false)
        mjpegLastCaptureStartedNs = 0L
        workerHandler?.removeCallbacks(mjpegCaptureRunnable)
""",
    "mjpeg release reset",
)

# Target up to 12 fps. Actual cadence self-throttles to the Samsung's real JPEG latency.
gpu = replace_once(
    gpu,
    """        private const val MAX_GPU_MJPEG_FPS = 5
""",
    """        private const val MAX_GPU_MJPEG_FPS = 12
        private const val MIN_GPU_MJPEG_QUALITY = 82
        private const val MAX_GPU_MJPEG_QUALITY = 92
        private const val MJPEG_CAPTURE_TIMEOUT_MS = 1_500L
""",
    "mjpeg constants",
)

GPU.write_text(gpu, encoding="utf-8")
print("Build 39 MJPEG flow-control patch applied")
