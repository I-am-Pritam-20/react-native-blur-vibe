package com.blurvibe

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

internal object BlurThreadPool {

  const val MIN_RADIUS = 2
  const val MAX_RADIUS = 100

  /** direction constants passed to the native slice function */
  const val DIRECTION_HORIZONTAL = 0
  const val DIRECTION_VERTICAL   = 1

  private val threadCount: Int =
    (Runtime.getRuntime().availableProcessors()).coerceIn(2, 5)

  private val executor: ExecutorService = Executors.newFixedThreadPool(
    threadCount,
    ThreadFactory { runnable ->
      Thread(runnable, "BlurVibeWorker").apply {
        priority = Thread.MIN_PRIORITY
        isDaemon = true
      }
    }
  )

  @Volatile private var loadAttempted = false
  @Volatile private var loaded = false

  /** True if the native library loaded successfully. Safe to call repeatedly. */
  fun isAvailable(): Boolean {
    if (!loadAttempted) {
      synchronized(this) {
        if (!loadAttempted) {
          loadAttempted = true
          loaded = try {
            System.loadLibrary("blurvibe_native")
            true
          } catch (_: Throwable) {
            false
          }
        }
      }
    }
    return loaded
  }

  /**
   * Runs one blur pass (horizontal or vertical) over [bitmap] in place,
   * splitting the work across the shared thread pool. [bitmap] must be
   * Bitmap.Config.ARGB_8888. Returns true only if every worker slice
   * reported success.
   */
  fun blurRound(bitmap: Bitmap, radius: Int, direction: Int): Boolean {
    if (!isAvailable()) return false
    if (bitmap.isRecycled) return false

    val clampedRadius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)
    val count = threadCount

    if (count == 1) {
      // Single-core fallback: skip pool/latch overhead entirely.
      return try {
        nativeBlurSlice(bitmap, clampedRadius, 1, 0, direction) == 0
      } catch (_: Throwable) {
        false
      }
    }

    val latch = CountDownLatch(count)
    val results = BooleanArray(count)

    for (index in 0 until count) {
      executor.execute {
        try {
          results[index] = nativeBlurSlice(bitmap, clampedRadius, count, index, direction) == 0
        } catch (_: Throwable) {
          results[index] = false
        } finally {
          latch.countDown()
        }
      }
    }

    try {
      latch.await()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    }

    return results.all { it }
  }

  @JvmStatic
  private external fun nativeBlurSlice(
    bitmap: Bitmap, radius: Int, threadCount: Int, threadIndex: Int, direction: Int
  ): Int
}
