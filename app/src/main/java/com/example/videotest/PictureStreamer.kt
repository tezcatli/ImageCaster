package com.example.videotest

/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.opengl.*
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.net.URL
import java.nio.ByteBuffer
import java.util.*


//import org.mp4parser.


//import java.awt.SystemColor.info


class PictureStreamer(
    val config: Config
) : HandlerThread("h264Streamer") {

    class Config(val width: Int, val height: Int, val bitrate: Int, val framerate: Int)


    private var throttleQueue = LinkedList<Unit>()

    // MediaCodec and encoding configuration
    private var encoder: MediaCodec? = null
    private var mime = "video/avc"
    private var presentationTimeUs = 0L
    private lateinit var mSize: Size
    private var streamStarted: Boolean = false

    // EGL
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // Surface provided by MediaCodec and used to get data produced by OpenGL
    private var surface: Surface? = null
    private lateinit var muxer: Mp4Muxer
    private lateinit var configData: ByteArray
    lateinit var handler: Handler
    private lateinit var renderer: TextureRenderer
    private var startStreamTime: Long = 0
    private var bitmap: Bitmap? = null
    private var started: Boolean = false

    var conditionVariable = ConditionVariable()

    companion object {
        fun buildPictureStreamer(config: Config): PictureStreamer {
            val pictureStreamer = PictureStreamer(config)
            pictureStreamer.start()
            pictureStreamer.handler = Handler(pictureStreamer.getLooper())
            return pictureStreamer
        }
    }


    override fun onLooperPrepared() {
        handler = object : Handler(looper) {
            override fun handleMessage(msg: Message?) {
                Log.e("VIDEOTEST", "Got message ${msg}")
            }
        }
    }


    fun startStreaming() {
        Log.e("VIDEOTEST", "encode thread is ${Thread.currentThread()}")

        muxer = Mp4Muxer(config)

        initEncoder()

        started = true;
        conditionVariable.open()
    }

    fun getMp4Stream(): Mp4Muxer.Mp4Stream {
        return muxer.getMp4Stream()
    }

    private fun selectCodec(mimeType: String): MediaCodecInfo? {
        val numCodecs = MediaCodecList.getCodecCount()
        for (i in 0 until numCodecs) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            if (!codecInfo.isEncoder) {
                continue
            }
            val types = codecInfo.supportedTypes
            for (j in types.indices) {
                if (types[j].equals(mimeType, ignoreCase = true)) {
                    return codecInfo
                }
            }
        }
        return null
    }

    private fun initEncoder() {
        Log.e("VIDEOTEST", "initEncoder thread is ${Thread.currentThread()}")



        encoder = MediaCodec.createEncoderByType(mime)

        val intra_refresh = encoder?.codecInfo?.getCapabilitiesForType(mime)
            ?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh)

        Log.e("VIDEOTEST", "Intra refresh support ${intra_refresh}")

        //val capabilities = MediaCodecInfo.CodecCapabilities(mime)

        // Try to find supported size by checking the resolution of first supplied image
        // This could also be set manually as parameter to TimeLapseEncoder
        //size = getSupportedSize(imageUris[0], context.contentResolver)

        Log.e("VIDEOTEST", "config.width = ${config.width}")

        mSize = Size(config.width, config.height)

        Log.e("VIDEOTEST", "width = ${mSize?.width}")

        val format = getFormat(mSize!!)

        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        var bundle = Bundle()
        bundle.putInt(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCProfileExtended)
        encoder?.setParameters(bundle)


        initEgl()

        renderer = TextureRenderer(config.width, config.height)

        val callback = object : MediaCodec.Callback() {
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("VIDEOTEST", e.message)
            }

            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                this@PictureStreamer.onOutputBufferAvailable(codec, index, info)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                this@PictureStreamer.onOutputFormatChanged(codec, format)
            }
        }

        encoder!!.setCallback(callback, this.handler)
        encoder!!.start()
        Log.e("VIDEOTEST", "Setup ended ${Thread.currentThread()}")
        //setupImage()
        //encode2()
    }

    private fun getSupportedSize(inBitmapUri: Uri, contentResolver: ContentResolver): Size {
        val first = MediaStore.Images.Media.getBitmap(contentResolver, inBitmapUri)
        return getBestSupportedResolution(encoder!!, mime, Size(first.width, first.height))
    }

    private fun getFormat(size: Size): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, size.width, size.height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        // format.setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.framerate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.framerate * 300000)
        //format.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, 10)

        return format
    }


    private fun initEgl() {
        surface = encoder!!.createInputSurface()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY)
            throw RuntimeException(
                "eglDisplay == EGL14.EGL_NO_DISPLAY: "
                        + GLUtils.getEGLErrorString(EGL14.eglGetError())
            )

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
            throw RuntimeException("eglInitialize(): " + GLUtils.getEGLErrorString(EGL14.eglGetError()))

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val nConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, nConfigs, 0)

        var err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext =
            EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        val surfaceAttribs = intArrayOf(

            EGL14.EGL_NONE
        )
        eglSurface =
            EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)

        err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            throw RuntimeException("eglMakeCurrent(): " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
    }


    fun onOutputBufferAvailable(
        codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
    ) {
        val outputBuffer: ByteBuffer = codec.getOutputBuffer(index)

        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            Log.v(
                "ENCODER",
                "New h264 frame received : size =  ${info.size}, ps = ${info.presentationTimeUs}"
            )

            if ((info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) {
                Log.e("VIDEOTEST", "Received partial frame");
            }

            muxer.pushFrame(
                outputBuffer, info.size,
                info.presentationTimeUs,
                info.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME
            )

            throttleQueue.pop()

        } else {
            Log.e("VIDEOTEST", "Received configuration data of ${outputBuffer.remaining()} bytes");
            //info.presentationTimeUs = presentationTimeUs

            muxer.pushConfig(outputBuffer, info.size)
        }

        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Log.e("VIDEOTEST", "End of stream")

            muxer.flushData();
        } else {

        }
        codec.releaseOutputBuffer(index, false)
        //}
    }

    fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        // Subsequent data will conform to new format.
        // Can ignore if using getOutputFormat(outputBufferId)
        Log.e("VIDEOTEST", "Video format changed $format")

    }

    fun setupImage(imageUrl: URL) {
        //val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
        val options = BitmapFactory.Options()

        options.inMutable = true

        if (bitmap != null) {
            options.inBitmap = bitmap
        }


        Thread({
            val timeStart = SystemClock.uptimeMillis()
            bitmap = BitmapFactory.decodeStream(
                imageUrl.openConnection().getInputStream(),
                null,
                options
            );
            val timeStop = SystemClock.uptimeMillis()
            Log.e("VIDEOTEST", "Jpeg fetched and decoded in ${timeStop - timeStart} ms")
            handler.post({
                renderer.loadImage(
                    bitmap!!,
                    getMvp((1.0 * bitmap!!.width) / bitmap!!.height, 16.0 / 9)
                )

                if (started) {
                    /*
            renderer.draw(
                mSize!!.width, mSize!!.height, bitmap,
                getMvp((1.0 * bitmap.width) / bitmap.height, 16.0 / 9)
            )
             */

                    Log.e("VIDEOTEST", "New image rendered to gl surface")
                    if (!streamStarted) {
                        streamStarted = true;
                        var syncFrame = Bundle()
                        syncFrame.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        encoder?.setParameters(syncFrame)
                        encode2()
                    }
                }
            })
        }).start()
    }

    private fun encode2() {
        // Render the bitmap/texture here
        if (startStreamTime == 0L) {
            startStreamTime = SystemClock.uptimeMillis() * 1000
            presentationTimeUs = startStreamTime - (1000000 / config.framerate) * 0
        } else {
            presentationTimeUs += 1000000 / config.framerate
        }

        handler.post({
            //handler?.postAtTime({
            encode2()

            Log.i(
                "VIDEOTEST",
                "DeltaTime: ${(SystemClock.uptimeMillis() - presentationTimeUs / 1000)}, remainingMuxerBuffer: ${muxer.remainingFreeBuffer()}, encoderOutstandingBuffer: ${throttleQueue.size}"
            )

            if ((muxer.remainingFreeBuffer() > 1) and (throttleQueue.size < 7)) {
                renderer.draw()

                EGLExt.eglPresentationTimeANDROID(
                    eglDisplay, eglSurface,
                    //(presentationTimeUs * 1000 * 1).toLong()
                    SystemClock.uptimeMillis() * 1000000
                )

                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                throttleQueue.add(Unit)
            } else {
                Log.e("VIDEOTEST", "Muxer queue full : skiping frame")
            }
        }
            //, presentationTimeUs / 1000
        )

    }

    private fun getMvp(pictureRatio: Double, screenRatio: Double): FloatArray {
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        if (pictureRatio < screenRatio) {
            //Matrix.scaleM(mvp, 0, 1f, -1f, 1f)
            Matrix.scaleM(mvp, 0, 1f * (pictureRatio / screenRatio).toFloat(), -1f, 1f)

        } else {
            Matrix.scaleM(mvp, 0, 1f, -1f * (screenRatio / pictureRatio).toFloat(), 1f)
        }

        return mvp
    }

    fun release() {

        handler.removeCallbacksAndMessages(null)
        encoder?.stop()
        encoder?.release()
        muxer.release()

        encoder = null


        releaseEgl()

        presentationTimeUs = 0L

    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }

        surface?.release()
        surface = null

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }


}

