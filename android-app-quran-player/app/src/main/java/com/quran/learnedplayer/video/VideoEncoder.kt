package com.quran.learnedplayer.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Encodes frames drawn on a [Bitmap] to H.264 and muxes them with the soundtrack into an MP4.
 *
 * Frames go through OpenGL to the encoder's input surface rather than through a Canvas on that
 * surface, because only EGL lets us stamp each frame with its time in the video
 * (`eglPresentationTimeANDROID`); a Canvas post is stamped with the wall clock, and a video
 * rendered faster or slower than real time would play at the wrong speed.
 *
 * Audio samples are written in step with the video ones, so the file is interleaved and starts
 * playing quickly when shared.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    output: File,
    private val audio: EncodedAudio,
) {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private var nextAudio = 0
    private var released = false
    private val audioFile = RandomAccessFile(audio.file, "r")

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var textureLoaded = false
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(QUAD)
        position(0)
    }

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate(width, height, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec = MediaCodec.createEncoderByType(MIME)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            setUpEgl(codec.createInputSurface())
            codec.start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Encodes [frame] at [presentationUs] into the video. */
    fun encode(frame: Bitmap, presentationUs: Long) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (!textureLoaded) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
            textureLoaded = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frame)
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationUs * 1000)
        EGL14.eglSwapBuffers(display, surface)
        drain(false)
    }

    /** Ends the video, writes the rest of the audio and closes the file. */
    fun finish() {
        codec.signalEndOfInputStream()
        drain(true)
        writeAudioUpTo(Long.MAX_VALUE)
        if (muxing) {
            muxer.stop()
            muxing = false
        }
        release()
    }

    /** Frees the encoder, muxer and EGL; safe to call more than once, and after a failure. */
    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
        muxing = false
        runCatching { audioFile.close() }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun drain(untilEnd: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    audioTrack = muxer.addTrack(audio.format)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxing) {
                        writeAudioUpTo(info.presentationTimeUs)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun writeAudioUpTo(presentationUs: Long) {
        if (!muxing) return
        val sampleInfo = MediaCodec.BufferInfo()
        while (nextAudio < audio.samples.size && audio.samples[nextAudio].presentationUs <= presentationUs) {
            val sample = audio.samples[nextAudio++]
            val bytes = ByteArray(sample.size)
            audioFile.seek(sample.offset)
            audioFile.readFully(bytes)
            sampleInfo.set(0, sample.size, sample.presentationUs, sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
            muxer.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), sampleInfo)
        }
    }

    private fun setUpEgl(inputSurface: android.view.Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL unavailable" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL makeCurrent failed" }

        program = linkProgram()
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(coord)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun linkProgram(): Int {
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
        }
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** Plenty for text over soft backgrounds: about 4.4 Mbit/s for 1080×1920 at 30 fps. */
        fun bitRate(width: Int, height: Int, fps: Int): Int = (width.toLong() * height * fps * 0.07).toInt()

        /**
         * The largest size, from full HD down, that this phone's H.264 encoder accepts for
         * [aspect]. Older encoders refuse 1080 wide in portrait.
         */
        fun supportedSize(aspect: VideoAspect, preferredScale: Float = 1f): Pair<Int, Int> {
            val capabilities = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) } }
                .mapNotNull { runCatching { it.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull() }
            for (factor in floatArrayOf(1f, 0.75f, 0.667f, 0.5f).map { it * preferredScale }) {
                val (w, h) = aspect.size(factor)
                if (capabilities.isEmpty() || capabilities.any { it.isSizeSupported(w, h) }) return w to h
            }
            return aspect.size(0.5f * preferredScale)
        }

        private val QUAD = floatArrayOf(
            // x, y, u, v — the bitmap's top row is v = 0, so v is flipped against GL's y.
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aCoord;
            varying vec2 vCoord;
            void main() { gl_Position = aPosition; vCoord = aCoord; }
        """

        private const val FRAGMENT = """
            precision mediump float;
            varying vec2 vCoord;
            uniform sampler2D uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vCoord); }
        """
    }
}
