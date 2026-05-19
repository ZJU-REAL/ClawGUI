package com.clawgui.ng.runtime.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Persists picker / camera images into the app sandbox so chat messages can
 * outlive the temporary content-URI grant the system hands us. Images are
 * downscaled to a reasonable max edge (so a 4K phone photo doesn't pin a
 * 30 MB blob to history) and re-encoded as JPEG with EXIF rotation baked in.
 *
 * Files live under `filesDir/attachments/`; trace export and clear-data both
 * naturally sweep them.
 */
object AttachmentStore {

    /** Bound the longest edge of stored images. 1280px is the upper threshold
     *  most VLMs (GLM-4.5V, Qwen-VL) accept without internal resize while still
     *  reading small text. Larger is wasted upload bandwidth + tokens. */
    private const val MAX_EDGE_PX = 1280

    /** Persist a content-URI'd image to the app sandbox. Returns the absolute
     *  file path on success, or null on any failure (caller surfaces a toast). */
    fun saveImage(context: Context, source: Uri): String? = runCatching {
        val dir = ensureDir(context)
        val target = File(dir, "img_${UUID.randomUUID()}.jpg")

        val bitmap = decodeDownscaled(context, source) ?: return@runCatching null
        val rotated = applyExifRotation(context, source, bitmap)
        FileOutputStream(target).use { out ->
            rotated.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
        target.absolutePath
    }.getOrNull()

    /** Delete a previously saved attachment. Returns false silently if missing. */
    fun delete(path: String): Boolean = runCatching {
        File(path).takeIf { it.exists() }?.delete() ?: false
    }.getOrDefault(false)

    /** Read back the bytes for a stored attachment (e.g. to base64-encode for VLM). */
    fun readBytes(path: String): ByteArray? =
        runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()

    private fun ensureDir(context: Context): File {
        val dir = File(context.filesDir, "attachments")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Two-pass bounds decode → BitmapFactory.Options.inSampleSize. Avoids
     *  decoding a 4000×3000 phone snap into memory just to throw most of it. */
    private fun decodeDownscaled(context: Context, source: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_EDGE_PX) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return cr.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Honour EXIF orientation tag so portrait photos don't land sideways. */
    private fun applyExifRotation(context: Context, source: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(source)?.use { ins ->
                ExifInterface(ins).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
