/*
 * blur_engine.c
 *
 * Native multi-threaded box blur operating directly on a locked Android
 * Bitmap's native pixel memory (AndroidBitmap_lockPixels) — no
 * getPixels()/setPixels() array copy through the JNI boundary, no
 * per-pixel JNI crossing.
 *
 * ── Algorithm ────────────────────────────────────────────────────────────
 *
 * Triangular-weighted incremental sliding-window blur (the well-known
 * "stack blur" technique): a circular buffer tracks the most recent
 * (2*radius+1) pixels per row/column, with running sums updated
 * incrementally as the window slides — O(1) work per output pixel
 * regardless of radius, rather than re-summing the whole window each step.
 * Division-free: a per-radius (multiply, shift) pair replaces the /(divisor)
 * needed to normalize each running sum, since integer multiply+shift is
 * cheaper than integer divide. Only RGB is blurred; alpha passes through
 * unchanged (backdrop content behind a BlurView is normally opaque, and
 * leaving alpha untouched avoids fringing at any semi-transparent edges).
 *
 * Threading: this function processes a horizontal or vertical pass over
 * ONLY a slice of rows/columns — [threadIndex*n/threadCount,
 * (threadIndex+1)*n/threadCount) — so multiple concurrent calls, each with
 * a different threadIndex, can process disjoint slices of the SAME bitmap
 * in parallel. See BlurThreadPool.kt for the calling side.
 *
 * ── Memory management ───────────────────────────────────────────────────────
 *
 * One heap allocation per call — a (2*radius+1)*3 byte scratch buffer for
 * the circular window (heap, not a variable-length stack array, so a large
 * radius can't overflow the stack) — freed on every return path before
 * that path returns.
 */

#include <jni.h>
#include <stdlib.h>
#include <android/log.h>
#include <android/bitmap.h>

#define LOG_TAG "BlurVibeNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CLAMP255(a) \
    ({__typeof__ (a) _a__ = (a); \
      _a__ < 0 ? 0 : _a__ > 255 ? 255 : _a__; })

/* Division-free normalization: running_sum * multiplyByRadius[radius] >>
 * shiftByRadius[radius] approximates running_sum / divisor for that
 * radius's triangular-kernel divisor, without an integer divide per pixel.
 * Indexed 0-254; radius is clamped to [2,100] by the Kotlin caller, safely
 * inside this range. */
static unsigned short const kMultiplyByRadius[255] =
{
        512,512,456,512,328,456,335,512,405,328,271,456,388,335,292,512,
        454,405,364,328,298,271,496,456,420,388,360,335,312,292,273,512,
        482,454,428,405,383,364,345,328,312,298,284,271,259,496,475,456,
        437,420,404,388,374,360,347,335,323,312,302,292,282,273,265,512,
        497,482,468,454,441,428,417,405,394,383,373,364,354,345,337,328,
        320,312,305,298,291,284,278,271,265,259,507,496,485,475,465,456,
        446,437,428,420,412,404,396,388,381,374,367,360,354,347,341,335,
        329,323,318,312,307,302,297,292,287,282,278,273,269,265,261,512,
        505,497,489,482,475,468,461,454,447,441,435,428,422,417,411,405,
        399,394,389,383,378,373,368,364,359,354,350,345,341,337,332,328,
        324,320,316,312,309,305,301,298,294,291,287,284,281,278,274,271,
        268,265,262,259,257,507,501,496,491,485,480,475,470,465,460,456,
        451,446,442,437,433,428,424,420,416,412,408,404,400,396,392,388,
        385,381,377,374,370,367,363,360,357,354,350,347,344,341,338,335,
        332,329,326,323,320,318,315,312,310,307,304,302,299,297,294,292,
        289,287,285,282,280,278,275,273,271,269,267,265,263,261,259
};

static unsigned char const kShiftByRadius[255] =
{
        9, 11, 12, 13, 13, 14, 14, 15, 15, 15, 15, 16, 16, 16, 16, 17,
        17, 17, 17, 17, 17, 17, 18, 18, 18, 18, 18, 18, 18, 18, 18, 19,
        19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 20, 20, 20,
        20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 21,
        21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 21,
        21, 21, 21, 21, 21, 21, 21, 21, 21, 21, 22, 22, 22, 22, 22, 22,
        22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
        22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 23,
        23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23,
        23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23,
        23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23,
        23, 23, 23, 23, 23, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
        24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
        24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
        24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
        24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24
};

/*
 * Processes rows [threadIndex*height/threadCount, (threadIndex+1)*height/
 * threadCount) for a horizontal pass (direction==0), or the equivalent
 * column slice for a vertical pass (direction==1).
 *
 * `stride` is passed explicitly (not assumed to equal width*4) — every
 * bitmap this is called on is created by our own Kotlin code via
 * Bitmap.createBitmap(w, h, ARGB_8888), where stride==width*4 holds in
 * practice, but reading the real value costs nothing and removes any
 * dependency on that assumption holding on every device.
 */
static void runBlurSlice(unsigned char *pixels, int width, int height,
                          int stride, int radius, int threadCount,
                          int threadIndex, int direction) {

  unsigned int x, y, xp, yp, i;
  unsigned int sp;
  unsigned int qmStart;
  unsigned char *qmPtr;
  unsigned char *srcPtr;
  unsigned char *dstPtr;

  unsigned long sumR, sumG, sumB;
  unsigned long sumInR, sumInG, sumInB;
  unsigned long sumOutR, sumOutG, sumOutB;

  unsigned int wm = (unsigned int) width - 1;
  unsigned int hm = (unsigned int) height - 1;
  unsigned int div = ((unsigned int) radius * 2) + 1;
  unsigned int mulSum = kMultiplyByRadius[radius];
  unsigned char shrSum = kShiftByRadius[radius];

  /* Heap, not a variable-length stack array — a large radius can't
   * overflow the stack this way. */
  unsigned char *window = (unsigned char *) malloc((size_t) div * 3);
  if (window == NULL) return;

  if (direction == 0) {
    /* Horizontal pass — this call's row slice. */
    int minY = threadIndex * height / threadCount;
    int maxY = (threadIndex + 1) * height / threadCount;

    for (y = (unsigned int) minY; y < (unsigned int) maxY; y++) {
      sumR = sumG = sumB = 0;
      sumInR = sumInG = sumInB = 0;
      sumOutR = sumOutG = sumOutB = 0;

      srcPtr = pixels + (size_t) stride * y;

      /* Initialize the window's left/center portion — edge-clamped by
       * reading the row's first pixel repeatedly, weighted ascending. */
      for (i = 0; i <= (unsigned int) radius; i++) {
        qmPtr = &window[3 * i];
        qmPtr[0] = srcPtr[0];
        qmPtr[1] = srcPtr[1];
        qmPtr[2] = srcPtr[2];
        sumR += srcPtr[0] * (i + 1);
        sumG += srcPtr[1] * (i + 1);
        sumB += srcPtr[2] * (i + 1);
        sumOutR += srcPtr[0];
        sumOutG += srcPtr[1];
        sumOutB += srcPtr[2];
      }

      /* Initialize the window's right portion — advancing (clamped at the
       * row's last valid pixel), weighted descending. */
      for (i = 1; i <= (unsigned int) radius; i++) {
        if (i <= wm) srcPtr += 4;
        qmPtr = &window[3 * (i + (unsigned int) radius)];
        qmPtr[0] = srcPtr[0];
        qmPtr[1] = srcPtr[1];
        qmPtr[2] = srcPtr[2];
        sumR += srcPtr[0] * ((unsigned int) radius + 1 - i);
        sumG += srcPtr[1] * ((unsigned int) radius + 1 - i);
        sumB += srcPtr[2] * ((unsigned int) radius + 1 - i);
        sumInR += srcPtr[0];
        sumInG += srcPtr[1];
        sumInB += srcPtr[2];
      }

      sp = (unsigned int) radius;
      xp = (unsigned int) radius;
      if (xp > wm) xp = wm;
      srcPtr = pixels + (size_t) stride * y + (size_t) xp * 4;
      dstPtr = pixels + (size_t) stride * y;

      for (x = 0; x < (unsigned int) width; x++) {
        dstPtr[0] = (unsigned char) CLAMP255((sumR * mulSum) >> shrSum);
        dstPtr[1] = (unsigned char) CLAMP255((sumG * mulSum) >> shrSum);
        dstPtr[2] = (unsigned char) CLAMP255((sumB * mulSum) >> shrSum);
        dstPtr += 4;

        sumR -= sumOutR;
        sumG -= sumOutG;
        sumB -= sumOutB;

        qmStart = sp + div - (unsigned int) radius;
        if (qmStart >= div) qmStart -= div;
        qmPtr = &window[3 * qmStart];

        sumOutR -= qmPtr[0];
        sumOutG -= qmPtr[1];
        sumOutB -= qmPtr[2];

        if (xp < wm) {
          srcPtr += 4;
          ++xp;
        }

        qmPtr[0] = srcPtr[0];
        qmPtr[1] = srcPtr[1];
        qmPtr[2] = srcPtr[2];

        sumInR += srcPtr[0];
        sumInG += srcPtr[1];
        sumInB += srcPtr[2];
        sumR += sumInR;
        sumG += sumInG;
        sumB += sumInB;

        ++sp;
        if (sp >= div) sp = 0;
        qmPtr = &window[sp * 3];

        sumOutR += qmPtr[0];
        sumOutG += qmPtr[1];
        sumOutB += qmPtr[2];
        sumInR -= qmPtr[0];
        sumInG -= qmPtr[1];
        sumInB -= qmPtr[2];
      }
    }

  } else {
    /* Vertical pass — this call's column slice. Structurally identical to
     * the horizontal pass above with rows/columns transposed. */
    int minX = threadIndex * width / threadCount;
    int maxX = (threadIndex + 1) * width / threadCount;

    for (x = (unsigned int) minX; x < (unsigned int) maxX; x++) {
      sumR = sumG = sumB = 0;
      sumInR = sumInG = sumInB = 0;
      sumOutR = sumOutG = sumOutB = 0;

      srcPtr = pixels + (size_t) x * 4;

      for (i = 0; i <= (unsigned int) radius; i++) {
        qmPtr = &window[3 * i];
        qmPtr[0] = srcPtr[0];
        qmPtr[1] = srcPtr[1];
        qmPtr[2] = srcPtr[2];
        sumR += srcPtr[0] * (i + 1);
        sumG += srcPtr[1] * (i + 1);
        sumB += srcPtr[2] * (i + 1);
        sumOutR += srcPtr[0];
        sumOutG += srcPtr[1];
        sumOutB += srcPtr[2];
      }

      for (i = 1; i <= (unsigned int) radius; i++) {
        if (i <= hm) srcPtr += (size_t) stride;
        qmPtr = &window[3 * (i + (unsigned int) radius)];
        qmPtr[0] = srcPtr[0];
        qmPtr[1] = srcPtr[1];
        qmPtr[2] = srcPtr[2];
        sumR += srcPtr[0] * ((unsigned int) radius + 1 - i);
        sumG += srcPtr[1] * ((unsigned int) radius + 1 - i);
        sumB += srcPtr[2] * ((unsigned int) radius + 1 - i);
        sumInR += srcPtr[0];
        sumInG += srcPtr[1];
        sumInB += srcPtr[2];
      }

      sp = (unsigned int) radius;
      yp = (unsigned int) radius;
      if (yp > hm) yp = hm;
      srcPtr = pixels + (size_t) stride * yp + (size_t) x * 4;
      dstPtr = pixels + (size_t) x * 4;

      for (y = 0; y < (unsigned int) height; y++) {
        dstPtr[0] = (unsigned char) CLAMP255((sumR * mulSum) >> shrSum);
        dstPtr[1] = (unsigned char) CLAMP255((sumG * mulSum) >> shrSum);
        dstPtr[2] = (unsigned char) CLAMP255((sumB * mulSum) >> shrSum);
        dstPtr += stride;

        sumR -= sumOutR;
        sumG -= sumOutG;
        sumB -= sumOutB;

        qmStart = sp + div - (unsigned int) radius;
        if (qmStart >= div) qmStart -= div;
        qmPtr = &window[3 * qmStart];

        sumOutR -= qmPtr[0];
        sumOutG -= qmPtr[1];
        sumOutB -= qmPtr[2];

        if (yp < hm) {
          srcPtr += stride;
          ++yp;
        }

        qmPtr[0] = srcPtr[0];
        qmPtr[1] = srcPtr[1];
        qmPtr[2] = srcPtr[2];

        sumInR += srcPtr[0];
        sumInG += srcPtr[1];
        sumInB += srcPtr[2];
        sumR += sumInR;
        sumG += sumInG;
        sumB += sumInB;

        ++sp;
        if (sp >= div) sp = 0;
        qmPtr = &window[sp * 3];

        sumOutR += qmPtr[0];
        sumOutG += qmPtr[1];
        sumOutB += qmPtr[2];
        sumInR -= qmPtr[0];
        sumInG -= qmPtr[1];
        sumInB -= qmPtr[2];
      }
    }
  }

  free(window);
}

JNIEXPORT jint JNICALL
Java_com_blurvibe_BlurThreadPool_nativeBlurSlice(
    JNIEnv *env, jclass clazz, jobject bitmap, jint radius,
    jint threadCount, jint threadIndex, jint direction) {

  (void) clazz;

  if (radius < 1) return 0;
  if (threadCount < 1) return -5;

  AndroidBitmapInfo info;
  int ret = AndroidBitmap_getInfo(env, bitmap, &info);
  if (ret != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("AndroidBitmap_getInfo failed, error=%d", ret);
    return -1;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    LOGE("Bitmap format is not RGBA_8888 (was %d)", info.format);
    return -2;
  }
  if (info.width == 0 || info.height == 0) {
    return 0;
  }

  void *pixelsRaw = NULL;
  ret = AndroidBitmap_lockPixels(env, bitmap, &pixelsRaw);
  if (ret != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("AndroidBitmap_lockPixels failed, error=%d", ret);
    return -3;
  }

  runBlurSlice((unsigned char *) pixelsRaw, (int) info.width, (int) info.height,
               (int) info.stride, (int) radius, (int) threadCount,
               (int) threadIndex, (int) direction);

  AndroidBitmap_unlockPixels(env, bitmap);
  return 0;
}
