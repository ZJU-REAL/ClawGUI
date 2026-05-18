package com.clawgui.ng.runtime.phone.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Downscale + recompress raw PNG screenshots before sending them to the VLM.
 *
 * **Alpha pitfall:** on some OEMs (Honor / MIUI) `screencap` yields an
 * ARGB_8888 PNG where the alpha channel is all-zero — pixels look fine when
 * viewed as PNG (display ignores alpha or composites against the launcher
 * wallpaper), but encoding straight to JPEG drops the alpha and renders every
 * pixel **black**, because JPEG has no alpha and the underlying RGB is
 * undefined when alpha == 0. The fix is to draw the decoded bitmap onto a
 * fresh ARGB_8888 canvas pre-filled with white, then encode that.
 *
 * Any decode/encode failure returns the original bytes so we never break the
 * agent in service of a perf optimization.
 */
object ScreenshotCompressor {

    private const val TAG = "ScreenshotCompressor"

    /** Quality preset. Long edge + JPEG quality. */
    enum class Quality(val longEdgePx: Int, val jpegQuality: Int, val label: String) {
        MEDIUM(1200, 75, "中 · 1200px"),       // recommended default
        LOW(900, 65, "低 · 900px"),
        TINY(640, 55, "极低 · 640px"),
        MICRO(480, 45, "微型 · 480px"),
        NANO(240, 30, "纳米 · 240px"),
        ATOM(160, 20, "原子 · 160px / Q20"),
        QUARK(100, 15, "夸克 · 100px / Q15"),
    }

    fun compress(src: ByteArray, quality: Quality): ByteArray {
        if (src.isEmpty()) return src
        val resize = quality.longEdgePx > 0
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(src, 0, src.size, opts)
            val srcLong = max(opts.outWidth, opts.outHeight)
            if (srcLong <= 0) return src

            var sample = 1
            if (resize) {
                while (srcLong / (sample * 2) >= quality.longEdgePx) sample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // Treat the PNG as opaque so even a zero-alpha source decodes
                // with fully-opaque pixels (most OEMs honor this).
                @Suppress("DEPRECATION")
                inPremultiplied = false
            }
            val decoded = BitmapFactory.decodeByteArray(src, 0, src.size, decodeOpts)
                ?: return src

            // Compute target size.
            val curLong = max(decoded.width, decoded.height)
            val (targetW, targetH) = if (resize && curLong > quality.longEdgePx) {
                val ratio = quality.longEdgePx.toFloat() / curLong
                val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                w to h
            } else decoded.width to decoded.height

            // Composite onto an opaque white background so JPEG doesn't render
            // alpha-zero pixels as black. Also handles the resize in one pass.
            val flat = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            val srcRect = android.graphics.Rect(0, 0, decoded.width, decoded.height)
            val dstRect = android.graphics.Rect(0, 0, targetW, targetH)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = false
            }
            canvas.drawBitmap(decoded, srcRect, dstRect, paint)
            decoded.recycle()

            val baos = ByteArrayOutputStream(96 * 1024)
            flat.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, baos)
            flat.recycle()
            val out = baos.toByteArray()
            Log.i(TAG, "ok q=${quality.name} src=${opts.outWidth}x${opts.outHeight} → ${targetW}x${targetH} bytes=${src.size}→${out.size}")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "compress failed, falling back to original", t)
            src
        }
    }
}
