package com.goatpro.ip

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Lifecycle independente da Activity para manter o pipeline CameraX ativo
 * enquanto uma transmissão está rodando em foreground service.
 */
object StreamingCameraLifecycle : LifecycleOwner {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle
        get() = registry

    fun setActive(active: Boolean) {
        val state = if (active) Lifecycle.State.STARTED else Lifecycle.State.CREATED
        if (Looper.myLooper() == Looper.getMainLooper()) {
            registry.currentState = state
        } else {
            mainHandler.post { registry.currentState = state }
        }
    }
}
