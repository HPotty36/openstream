@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.openstream.watch

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.net.URI

/**
 * YouTube sends a stream asked for from a point to its end at about twice the speed it plays,
 * so a busy scene or a slow moment on the network runs the player dry a few seconds in.
 * A piece asked for with range= in the address comes at full speed, so the player reads in pieces.
 */
object YouTubePieces {
    const val SIZE = 4L shl 20

    /** The stream's length in bytes from the clen= in its address; null for anything not a YouTube stream. */
    fun length(url: String): Long? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return null
        return Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** The address of the bytes from [from] up to, not including, [until]. */
    fun address(url: String, from: Long, until: Long): String =
        url + (if ('?' in url) "&" else "?") + "range=$from-${until - 1}"

    /** Where the piece starting at [from] ends, given the stream ends at [end]. */
    fun until(from: Long, end: Long, size: Long = SIZE): Long = minOf(from + size, end)
}

/** Reads YouTube streams through [upstream] one piece at a time; anything else passes straight through. */
class PiecedDataSource(private val upstream: DataSource) : DataSource {
    private var spec: DataSpec? = null
    private var url = ""
    private var position = 0L
    private var end = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val total = YouTubePieces.length(dataSpec.uri.toString())
        if (total == null) {
            spec = null
            return upstream.open(dataSpec)
        }
        if (dataSpec.position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        spec = dataSpec
        url = dataSpec.uri.toString()
        position = dataSpec.position
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total else minOf(total, dataSpec.position + dataSpec.length)
        if (position < end) openPiece()
        return end - position
    }

    private var pieceStart = 0L
    private var pieceFrom = 0L

    private fun openPiece() {
        if (dev.openstream.BuildConfig.DEBUG) {
            pieceStart = System.nanoTime()
            pieceFrom = position
        }
        val piece = spec!!.buildUpon()
            .setUri(YouTubePieces.address(url, position, YouTubePieces.until(position, end)))
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        upstream.open(piece)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (spec == null) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            // This piece is done; the next one starts where it stopped.
            if (dev.openstream.BuildConfig.DEBUG) {
                val seconds = (System.nanoTime() - pieceStart) / 1e9
                android.util.Log.d("Pieces", "piece ${(position - pieceFrom) shr 10}KB in ${"%.2f".format(seconds)}s = ${"%.1f".format((position - pieceFrom) * 8 / seconds / 1e6)}Mbps")
            }
            upstream.close()
            openPiece()
            read = upstream.read(buffer, offset, length)
            // An empty piece before the end: let the player retry rather than take the stream as over.
            if (read == C.RESULT_END_OF_INPUT) throw EOFException("YouTube sent an empty piece at $position of $end")
        }
        position += read
        return read
    }

    override fun getUri(): Uri? = spec?.uri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
