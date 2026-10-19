package com.example.prayer

import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VideoGenerator {
    fun generate(context: Context, prayerLine: String, outputFile: File) {
        val width = 1080
        val height = 1920
        val fps = 30
        val durationSec = 6
        val totalFrames = fps * durationSec

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8000000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1

        val egl = EGLHelper()
        egl.start(inputSurface)
        val glRenderer = GLTextRenderer()
        glRenderer.init()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val typeface = Typeface.createFromAsset(context.assets, "font.ttf")
        val headingPaint = Paint().apply {
            color = Color.YELLOW
            this.typeface = typeface
            textSize = 100f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        
        // Auto-size text: Start at 70f, shrink if needed to fit within 680x1320
        val textWidth = 680f
        val textMaxHeight = 1170f
        var optimalTextSize = 70f
        val minTextSize = 40f
        
        val textPaint = TextPaint().apply {
            color = Color.WHITE
            this.typeface = typeface
            isAntiAlias = true
        }
        
        // Find optimal font size that fits the text
        while (optimalTextSize >= minTextSize) {
            textPaint.textSize = optimalTextSize
            val layout = StaticLayout.Builder.obtain(prayerLine, 0, prayerLine.length, textPaint, textWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            
            if (layout.height <= textMaxHeight) {
                break // Text fits at this size
            }
            optimalTextSize -= 2f // Shrink by 2f and try again
        }
        
        textPaint.textSize = optimalTextSize

        for (i in 0 until totalFrames) {
            canvas.drawColor(Color.BLACK)
            canvas.drawText("PRAYER", width / 2f, 350f, headingPaint)
            
            val textX = (width - textWidth) / 2f
            val textY = 450f
            
            val staticLayout = StaticLayout.Builder.obtain(prayerLine, 0, prayerLine.length, textPaint, textWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.3f)
                .build()

            canvas.save()
            canvas.translate(textX, textY)
            canvas.clipRect(0f, 0f, textWidth, textMaxHeight)
            staticLayout.draw(canvas)
            canvas.restore()

            glRenderer.drawBitmap(bitmap)
            egl.swap()

            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                    
                    if (bufferInfo.size > 0 && outputBuffer != null) {
                        if (trackIndex == -1) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }

        codec.signalEndOfInputStream()
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
        egl.stop()
        bitmap.recycle()
    }
}

class EGLHelper {
    private var eglDisplay: javax.microedition.khronos.egl.EGLDisplay = javax.microedition.khronos.egl.EGL10.EGL_NO_DISPLAY
    private var eglContext: javax.microedition.khronos.egl.EGLContext = javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT
    private var eglSurface: javax.microedition.khronos.egl.EGLSurface = javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE
    private var egl: javax.microedition.khronos.egl.EGL10? = null

    fun start(surface: android.view.Surface) {
        egl = javax.microedition.khronos.egl.EGLContext.getEGL() as javax.microedition.khronos.egl.EGL10
        eglDisplay = egl!!.eglGetDisplay(javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        egl!!.eglInitialize(eglDisplay, version)
        val configAttribs = intArrayOf(
            javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
            javax.microedition.khronos.egl.EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        egl!!.eglChooseConfig(eglDisplay, configAttribs, configs, 1, numConfigs)
        val contextAttribs = intArrayOf(0x3098, 2, javax.microedition.khronos.egl.EGL10.EGL_NONE)
        eglContext = egl!!.eglCreateContext(eglDisplay, configs[0], javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT, contextAttribs)
        eglSurface = egl!!.eglCreateWindowSurface(eglDisplay, configs[0], surface, null)
        egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun swap() { egl?.eglSwapBuffers(eglDisplay, eglSurface) }

    fun stop() {
        egl?.eglMakeCurrent(eglDisplay, javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE, javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE, javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT)
        egl?.eglDestroySurface(eglDisplay, eglSurface)
        egl?.eglDestroyContext(eglDisplay, eglContext)
        egl?.eglTerminate(eglDisplay)
    }
}

class GLTextRenderer {
    private var program = 0; private var textureId = 0; private var posHandle = 0; private var texHandle = 0
    private val vertexShaderCode = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }"
    private val fragmentShaderCode = "precision mediump float; uniform sampler2D uTexture; varying vec2 vTexCoord; void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }"

    private val vertexBuffer = ByteBuffer.allocateDirect(16 * 4).run { order(ByteOrder.nativeOrder()); asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) } }
    private val texBuffer = ByteBuffer.allocateDirect(16 * 4).run { order(ByteOrder.nativeOrder()); asFloatBuffer().apply { put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0) } }

    fun init() {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
        posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    fun drawBitmap(bitmap: Bitmap) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }
}
