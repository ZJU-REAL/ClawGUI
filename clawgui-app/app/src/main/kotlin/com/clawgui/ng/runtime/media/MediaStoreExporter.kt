package com.clawgui.ng.runtime.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a chat-attachment image into the system gallery so on-device
 * agents (and the user) can find it as a regular photo. Uses MediaStore so
 * no WRITE_EXTERNAL_STORAGE permission is needed on any Android version.
 *
 * The exported path / display name is what we tell PhoneAgent to look for
 * in the system Gallery / picker.
 */
object MediaStoreExporter {

    data class Exported(
        val contentUri: Uri,
        /** Display name as it appears in Gallery. Convenient hint for the agent. */
        val displayName: String,
    )

    /**
     * Copy [sourceFile] into `Pictures/ClawGUI/`. Returns null on failure
     * (caller should fall back to telling the agent to use the file picker
     * by hand). [displayLabel] becomes the photo's filename so the agent
     * can recognise which one to pick if multiple were exported.
     */
    fun exportToGallery(
        context: Context,
        sourceFile: File,
        displayLabel: String,
    ): Exported? = runCatching {
        if (!sourceFile.exists()) return@runCatching null
        val name = sanitize(displayLabel) + "_" + System.currentTimeMillis() + ".jpg"
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/ClawGUI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching null
            val publish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publish, null, null)
            return@runCatching Exported(uri, name)
        }

        // Legacy path (API 26-28): write into Pictures/ClawGUI then notify
        // MediaScanner so the gallery picks it up.
        val pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(pics, "ClawGUI").apply { if (!exists()) mkdirs() }
        val target = File(dir, name)
        sourceFile.inputStream().use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        val uri = Uri.fromFile(target)
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null
        )
        Exported(uri, name)
    }.getOrNull()

    private fun sanitize(label: String): String =
        label.ifBlank { "clawgui" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(24)
            .ifBlank { "clawgui" }
}
