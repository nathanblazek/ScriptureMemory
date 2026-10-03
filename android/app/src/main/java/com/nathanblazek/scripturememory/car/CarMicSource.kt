package com.nathanblazek.scripturememory.car

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresPermission
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import com.nathanblazek.scripturememory.speech.AudioSource
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * The car's microphone, via Android Auto. Audio is pumped into a fresh pipe for each utterance the
 * speech recognizer listens to.
 */
class CarMicSource @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(carContext: CarContext) : AudioSource {
    private val record = CarAudioRecord.create(carContext)
    private val lock = Any()
    private var out: OutputStream? = null
    @Volatile private var running = false

    override val sampleRate: Int get() = CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE

    override fun open(): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        synchronized(lock) {
            runCatching { out?.close() }
            out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        }
        if (!running) {
            running = true
            record.startRecording()
            thread(name = "car-mic", isDaemon = true) { pump() }
        }
        return read
    }

    private fun pump() {
        val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        while (running) {
            val n = record.read(buffer, 0, buffer.size)
            if (n < 0) break
            if (n == 0) continue
            synchronized(lock) {
                try {
                    out?.write(buffer, 0, n)
                } catch (e: IOException) {
                    // The recognizer finished this utterance and closed its end; wait for the next pipe.
                    runCatching { out?.close() }
                    out = null
                }
            }
        }
    }

    override fun close() {
        if (running) {
            running = false
            runCatching { record.stopRecording() }
        }
        synchronized(lock) {
            runCatching { out?.close() }
            out = null
        }
    }
}
