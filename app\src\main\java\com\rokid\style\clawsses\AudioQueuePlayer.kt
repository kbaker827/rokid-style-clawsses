package com.rokid.style.clawsses

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays MP3 audio chunks sequentially without overlap.
 *
 * Chunks are enqueued via [enqueue] and played one after another.
 * Each chunk is written to a temporary file (MediaPlayer can't play from a
 * raw byte array without a content provider), then deleted after playback.
 *
 * Call [start] once to begin the consumer loop.
 * Call [stop] to halt playback and discard the queue.
 * Call [clear] to discard queued items without stopping the player.
 */
class AudioQueuePlayer(private val cacheDir: File) {

    companion object {
        private const val TAG = "AudioQueuePlayer"
    }

    private val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val running = AtomicBoolean(false)

    @Volatile private var currentPlayer: MediaPlayer? = null

    /** Enqueue an MP3 byte array for sequential playback. */
    fun enqueue(mp3Bytes: ByteArray) {
        queue.trySend(mp3Bytes)
    }

    /** Start the playback consumer. Must be called once before [enqueue]. */
    fun start() {
        if (running.getAndSet(true)) return
        scope.launch {
            for (mp3Bytes in queue) {
                if (!running.get()) break
                playBlocking(mp3Bytes)
            }
        }
    }

    /** Stop playback and discard any pending items in the queue. */
    fun stop() {
        running.set(false)
        currentPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping player: ${e.message}")
            }
        }
        currentPlayer = null
        // Drain the channel
        while (true) {
            queue.tryReceive().getOrNull() ?: break
        }
    }

    /** Discard all queued items but keep the player running for the current item. */
    fun clear() {
        while (true) {
            queue.tryReceive().getOrNull() ?: break
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun playBlocking(mp3Bytes: ByteArray) {
        val tmpFile = File(cacheDir, "tts_${UUID.randomUUID()}.mp3")
        try {
            FileOutputStream(tmpFile).use { it.write(mp3Bytes) }
            val latch = java.util.concurrent.CountDownLatch(1)
            val player = MediaPlayer()
            currentPlayer = player
            player.setDataSource(tmpFile.absolutePath)
            player.setOnCompletionListener {
                it.release()
                currentPlayer = null
                latch.countDown()
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                mp.release()
                currentPlayer = null
                latch.countDown()
                true
            }
            player.prepare()
            player.start()
            latch.await()
        } catch (e: Exception) {
            Log.e(TAG, "playBlocking error: ${e.message}")
        } finally {
            tmpFile.delete()
        }
    }
}
