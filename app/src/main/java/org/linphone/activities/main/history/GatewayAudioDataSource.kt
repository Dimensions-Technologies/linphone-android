package org.linphone.activities.main.history

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.services.RecordingsService
import org.linphone.services.VoicemailAudioException
import org.linphone.services.VoicemailBoxService
import retrofit2.HttpException

/**
 * Plays call recordings and voicemails from the gateway. Each is fetched (with the user's token)
 * into the cache the first time the player opens it, so a playlist can list them all without
 * fetching any until it's played.
 */
@UnstableApi
class GatewayAudioDataSource : BaseDataSource(false) {
    private val file = FileDataSource()
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val local = fetch(dataSpec.uri)
        val length = file.open(dataSpec.buildUpon().setUri(Uri.fromFile(local)).build())
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = file.read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file.close()
        } finally {
            transferEnded()
        }
    }

    // Runs on the player's loading thread
    private fun fetch(uri: Uri): File {
        val segments = uri.pathSegments
        return try {
            runBlocking {
                when (uri.host) {
                    GatewayAudioUris.HOST_RECORDING -> RecordingsService.getInstance(
                        coreContext.context
                    )
                        .getRecordingAudio(segments[0], segments[1])
                    GatewayAudioUris.HOST_VOICEMAIL -> VoicemailBoxService.getAudio(
                        segments[0],
                        segments[1]
                    )
                    else -> throw IOException("Unknown audio $uri")
                }
            }
        } catch (e: HttpException) {
            throw GatewayAudioException(e.code(), e)
        } catch (e: VoicemailAudioException) {
            throw GatewayAudioException(e.code, e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = GatewayAudioDataSource()
    }
}

/** Links the player opens through GatewayAudioDataSource. */
object GatewayAudioUris {
    private const val SCHEME = "gateway-audio"
    const val HOST_RECORDING = "recording"
    const val HOST_VOICEMAIL = "voicemail"

    fun recording(sessionId: String, recordingId: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST_RECORDING).appendPath(sessionId).appendPath(
            recordingId
        ).build()

    fun voicemail(boxId: String, mediaId: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST_VOICEMAIL).appendPath(boxId).appendPath(mediaId).build()
}

/** The gateway refused the audio: 404 if it no longer exists, 402 if there's no credit to fetch it. */
class GatewayAudioException(val code: Int, cause: Throwable) : IOException(
    "Failed to fetch audio ($code)",
    cause
)
