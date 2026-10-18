package com.riptv.tv

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.nio.ByteOrder

/**
 * La TDT emite el audio en MPEG-1 Layer II (MP2) y muchos televisores y cajas Android no traen decodificador
 * para él: el canal se ve pero no se oye. Este decodificador por software (JLayer) lo cubre.
 */
@UnstableApi
private class Mp2Decoder(private var channels: Int, private var sampleRate: Int) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<DecoderInputBuffer>(INPUT_BUFFERS) as Array<DecoderInputBuffer>),
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<SimpleDecoderOutputBuffer>(OUTPUT_BUFFERS) as Array<SimpleDecoderOutputBuffer>),
    ) {

    private var decoder = Decoder()

    val outputChannels get() = channels
    val outputSampleRate get() = sampleRate

    override fun getName() = "Mp2Decoder"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(this::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("Error inesperado al decodificar MP2", error)

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
        if (reset) decoder = Decoder()
        val data = inputBuffer.data ?: return null
        val bytes = ByteArray(data.remaining()).also { data.get(it) }
        val stream = Bitstream(ByteArrayInputStream(bytes))
        val pcm = ArrayList<ShortArray>()
        var total = 0
        try {
            while (true) {
                val header = stream.readFrame() ?: break
                val samples = decoder.decodeFrame(header, stream) as SampleBuffer
                stream.closeFrame()
                pcm += samples.buffer.copyOf(samples.bufferLength)
                total += samples.bufferLength
                channels = samples.channelCount
                sampleRate = samples.sampleFrequency
            }
        } catch (e: Exception) {
            // Un fragmento dañado se salta: se oye un instante de silencio en lugar de cortar la reproducción.
        }
        if (total == 0) {
            outputBuffer.shouldBeSkipped = true
            return null
        }
        val out = outputBuffer.init(inputBuffer.timeUs, total * 2).order(ByteOrder.LITTLE_ENDIAN)
        val view = out.asShortBuffer()
        pcm.forEach { view.put(it) }
        return null
    }

    private companion object {
        const val INPUT_BUFFERS = 4
        const val OUTPUT_BUFFERS = 4
    }
}

@UnstableApi
private class Mp2AudioRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<Mp2Decoder>(handler, listener, sink) {

    override fun getName() = "Mp2AudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_MPEG_L2 && format.sampleMimeType != MimeTypes.AUDIO_MPEG_L1) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        val pcm = pcmFormat(format.channelCount.coerceAtLeast(1), format.sampleRate)
        return if (sinkSupportsFormat(pcm)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) =
        Mp2Decoder(format.channelCount.coerceAtLeast(1), format.sampleRate)

    override fun getOutputFormat(decoder: Mp2Decoder): Format = pcmFormat(decoder.outputChannels, decoder.outputSampleRate)

    private fun pcmFormat(channels: Int, sampleRate: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(channels)
        .setSampleRate(sampleRate)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()
}

/**
 * Añade el decodificador de MP2 tras los del dispositivo (que tienen preferencia, porque suelen ir por
 * hardware) y activa los decodificadores de la extensión FFmpeg (AC-3, E-AC-3, DTS…) en la misma posición.
 */
@UnstableApi
class AppRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
        out.add(Mp2AudioRenderer(eventHandler, eventListener, audioSink))
    }
}
