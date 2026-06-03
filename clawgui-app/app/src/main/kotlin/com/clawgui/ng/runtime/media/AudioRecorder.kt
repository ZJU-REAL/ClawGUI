package com.clawgui.ng.runtime.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Records audio from the microphone and saves it as a 16kHz mono PCM WAV file
 * suitable for Whisper-compatible STT APIs. Lifecycle:
 *
 *   val recorder = AudioRecorder(context)
 *   recorder.start()          // begins capturing
 *   ...
 *   val file = recorder.stop() // returns the WAV file (or null on error)
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingFile: File? = null
    @Volatile
    private var isRecording = false

    val recording: Boolean get() = isRecording

    /**
     * Start recording. Call from a coroutine scope — this suspends and
     * continuously writes PCM data until [stop] is called.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            record.release()
            return@withContext
        }

        val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
        recordingFile = outFile
        audioRecord = record
        isRecording = true

        record.startRecording()
        Log.i(TAG, "Recording started → ${outFile.absolutePath}")

        FileOutputStream(outFile).use { fos ->
            // Write a placeholder WAV header — we'll patch the sizes on stop.
            writeWavHeader(fos, 0)

            val buffer = ByteArray(bufferSize)
            var totalBytes = 0L
            while (isRecording && isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    fos.write(buffer, 0, read)
                    totalBytes += read
                }
            }
            fos.flush()
            // Patch the WAV header with the actual data length.
            patchWavHeader(outFile, totalBytes)
        }

        record.stop()
        record.release()
        audioRecord = null
        Log.i(TAG, "Recording stopped, file size=${outFile.length()} bytes")
    }

    /** Stop recording and return the WAV file. Null if recording never started. */
    fun stop(): File? {
        isRecording = false
        return recordingFile
    }

    private fun writeWavHeader(fos: FileOutputStream, dataSize: Long) {
        val totalSize = 36 + dataSize
        val header = ByteArray(44)
        // RIFF chunk
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalSize.toInt())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt sub-chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16) // sub-chunk size
        writeShort(header, 20, 1) // PCM format
        writeShort(header, 22, 1) // mono
        writeInt(header, 24, SAMPLE_RATE)
        writeInt(header, 28, SAMPLE_RATE * 2) // byte rate (16-bit mono)
        writeShort(header, 32, 2) // block align
        writeShort(header, 34, 16) // bits per sample
        // data sub-chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, dataSize.toInt())
        fos.write(header)
    }

    private fun patchWavHeader(file: File, dataSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            val totalSize = 36 + dataSize
            raf.seek(4); raf.writeIntLE(totalSize.toInt())
            raf.seek(40); raf.writeIntLE(dataSize.toInt())
        }
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
