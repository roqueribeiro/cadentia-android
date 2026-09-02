package com.levelhard.cadentia.features.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM estéreo intercalado → M4A (AAC-LC 192 kbps) — o papel do
 * AVAudioFile(kAudioFormatMPEG4AAC) do mixdown do iOS, via MediaCodec +
 * MediaMuxer. Síncrono; chame fora do main.
 */
object AacEncoder {
    private const val BIT_RATE = 192_000
    private const val CHANNELS = 2
    private const val TIMEOUT_US = 10_000L

    fun encode(interleaved: FloatArray, sampleRate: Int, out: File): File {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack = -1
        var muxerStarted = false

        // 16-bit little-endian, o formato de entrada clássico do encoder.
        val pcmBytes = ByteBuffer.allocate(interleaved.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in interleaved) {
            pcmBytes.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        pcmBytes.flip()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var presentationUs = 0L
        val bytesPerSecond = sampleRate.toLong() * CHANNELS * 2

        try {
            while (true) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        if (pcmBytes.hasRemaining()) {
                            val input = codec.getInputBuffer(inputIndex)!!
                            input.clear()
                            val chunk = minOf(input.remaining(), pcmBytes.remaining())
                            val limited = pcmBytes.duplicate()
                            limited.limit(limited.position() + chunk)
                            input.put(limited)
                            pcmBytes.position(pcmBytes.position() + chunk)
                            codec.queueInputBuffer(inputIndex, 0, chunk, presentationUs, 0)
                            presentationUs += chunk * 1_000_000L / bytesPerSecond
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            muxer.writeSampleData(muxerTrack, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        return out
    }
}
