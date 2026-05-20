package com.clawgui.ng.runtime.feishu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Builds the JPEG bytes we ship back to a Feishu chat when a PhoneAgent run
 * driven from Feishu finishes. Two flavours:
 *
 *  - [finalShot] takes the last step's screenshot off the trace recorder
 *    and re-encodes it at ~80% quality so the Feishu upload stays under
 *    the 10 MB image cap.
 *
 *  - [compositeLong] vertically stitches every step's screenshot into one
 *    long image with a stripe label per step (number + action name). Caps
 *    at [maxSteps] frames to keep the upload size sane — if the run had
 *    more steps we draw a "...省略 N 步..." marker. Falls back to null
 *    when the trace dir has zero usable frames (caller then degrades to
 *    finalShot).
 */
object ReplyImageBuilder {

    private const val TARGET_FRAME_WIDTH = 512
    private const val LABEL_HEIGHT = 36
    private const val JPEG_QUALITY = 78
    private const val MAX_STEPS_DEFAULT = 20

    fun finalShot(runDir: File?): ByteArray? {
        if (runDir == null || !runDir.isDirectory) return null
        val last = listStepJpegs(runDir).lastOrNull() ?: return null
        val bmp = decode(last) ?: return null
        return encode(bmp).also { bmp.recycle() }
    }

    fun compositeLong(
        runDir: File?,
        actionLabels: List<String>,   // index i = label for step (i + 1)
        maxSteps: Int = MAX_STEPS_DEFAULT,
    ): ByteArray? {
        if (runDir == null || !runDir.isDirectory) return null
        val allFrames = listStepJpegs(runDir)
        if (allFrames.isEmpty()) return null

        // Sample down to maxSteps frames if needed — first, last, and an
        // evenly-spaced subset in between so the user still sees both the
        // starting state and the final outcome.
        val (selected, omitted) = sampleFrames(allFrames, maxSteps)
        val bitmaps = selected.mapNotNull { (origIndex, file) ->
            val bmp = decode(file) ?: return@mapNotNull null
            origIndex to bmp
        }
        if (bitmaps.isEmpty()) return null

        // Compute the canvas. We snap every frame to TARGET_FRAME_WIDTH so
        // they stack cleanly even if device resolution varies between
        // captures (e.g. orientation flip mid-task).
        val scaled = bitmaps.map { (idx, bmp) ->
            val sw = TARGET_FRAME_WIDTH
            val sh = (bmp.height.toLong() * sw / bmp.width).toInt().coerceAtLeast(1)
            val out = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            if (out !== bmp) bmp.recycle()
            idx to out
        }
        val totalHeight = scaled.sumOf { it.second.height + LABEL_HEIGHT } +
            if (omitted > 0) LABEL_HEIGHT else 0
        val canvasBmp = Bitmap.createBitmap(
            TARGET_FRAME_WIDTH, totalHeight, Bitmap.Config.RGB_565,
        )
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.WHITE)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val stripePaint = Paint().apply { color = 0xFF003F88.toInt() } // ZJU blue
        val omitPaint = Paint().apply { color = 0xFF8A8E94.toInt() }

        var y = 0
        scaled.forEach { (idx, bmp) ->
            // Step label strip
            canvas.drawRect(0f, y.toFloat(), TARGET_FRAME_WIDTH.toFloat(),
                (y + LABEL_HEIGHT).toFloat(), stripePaint)
            val label = labelAt(actionLabels, idx)
            canvas.drawText(label, 12f, (y + LABEL_HEIGHT - 12).toFloat(), labelPaint)
            y += LABEL_HEIGHT
            // Frame
            canvas.drawBitmap(bmp, 0f, y.toFloat(), null)
            y += bmp.height
            bmp.recycle()
        }
        if (omitted > 0) {
            canvas.drawRect(0f, y.toFloat(), TARGET_FRAME_WIDTH.toFloat(),
                (y + LABEL_HEIGHT).toFloat(), omitPaint)
            canvas.drawText("…省略 $omitted 步…", 12f,
                (y + LABEL_HEIGHT - 12).toFloat(), labelPaint)
        }
        val bytes = encode(canvasBmp)
        canvasBmp.recycle()
        return bytes
    }

    // Reservoir-ish sampling: always keep first + last, fill remaining slots
    // by even stride. Returns (selected = list of (original 1-based index,
    // file)) and how many frames got dropped from the middle.
    private fun sampleFrames(
        frames: List<File>,
        maxSteps: Int,
    ): Pair<List<Pair<Int, File>>, Int> {
        if (frames.size <= maxSteps) {
            return frames.mapIndexed { i, f -> (i + 1) to f } to 0
        }
        val out = mutableListOf<Pair<Int, File>>()
        out += 1 to frames.first()
        val interior = maxSteps - 2
        val span = frames.size - 2
        for (i in 1..interior) {
            val pos = 1 + (i.toLong() * span / (interior + 1)).toInt()
            out += (pos + 1) to frames[pos]
        }
        out += frames.size to frames.last()
        return out to (frames.size - maxSteps)
    }

    private fun listStepJpegs(runDir: File): List<File> =
        runDir.listFiles { f -> f.isFile && f.name.matches(Regex("""step_\d+\.jpg""")) }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun decode(f: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    private fun encode(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(bmp.byteCount / 4)
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun labelAt(labels: List<String>, oneBasedIndex: Int): String {
        val i = oneBasedIndex - 1
        return if (i in labels.indices) "Step $oneBasedIndex · ${labels[i]}"
        else "Step $oneBasedIndex"
    }
}
