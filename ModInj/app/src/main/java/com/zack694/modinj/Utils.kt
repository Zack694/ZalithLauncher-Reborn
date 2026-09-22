package com.zack694.modinj

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** App-wide single background executor — one thread, minimal memory. */
object ThreadPool {
    private val counter = AtomicInteger(0)

    val single: ExecutorService = Executors.newFixedThreadPool(2, ThreadFactory { r ->
        Thread(r, "modinj-worker-${counter.incrementAndGet()}").apply { isDaemon = false }
    })
}

object ViewCompatCompat {
    /**
     * Pads the root view by system-bar + display-cutout insets so content
     * never sits under the notch; the window background stays black behind it.
     */
    fun applyInsetsPadding(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
