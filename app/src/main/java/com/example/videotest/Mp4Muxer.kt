package com.example.videotest

import android.util.Log
import java.io.*

import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class Mp4Muxer @Throws(Exception::class)
constructor(internal val config: PictureStreamer.Config) {

    class Mp4Stream(var queue : LinkedBlockingQueue<ByteBuffer>) : InputStream() {
        var offset: Int = 0
        var buffer : ByteBuffer = ByteBuffer.allocate(0)


        override fun read(data: ByteArray, off: Int, len: Int): Int {
            var readLen: Int = 0
            var totalLen: Int = len


            if ((totalLen + off) > data.size) {
                throw IndexOutOfBoundsException()
            }

            //while (readLen != totalLen) {
            while (readLen  == 0) {
                var remaining = buffer.remaining()

                if (remaining > (totalLen - readLen)) {
                    buffer.get(data, off + readLen, totalLen - readLen)
                    readLen = totalLen
                } else {
                    buffer.get(data, off + readLen, remaining)
                    readLen += remaining
                    while (buffer.remaining() == 0) {
                        buffer = queue.take()
                    }
                    //return readLen
                    //
                }
            }
            Log.v("MUXER", "Read ${readLen} bytes")
            return readLen
        }

        override fun read(): Int {

            while (buffer.remaining() == 0) {
                buffer = queue.take()
            }
            return buffer.getChar().toInt()
        }

        override fun read(data: ByteArray): Int {
            return read(data, 0, data.size)
        }

        override fun markSupported(): Boolean {
            return false
        }

        override fun available(): Int {

            if ((buffer.remaining()) == 0 && (queue.isEmpty())) {
                return 0;
            } else {
                while (buffer.remaining() == 0) {
                    buffer = queue.take()
                }
                return buffer.remaining()
            }
        }

    }


    private val native_custom_data: Long = 0      // Native code will use this to keep private data


    private external fun nativeInit(config: PictureStreamer.Config)
    private external fun nativeRelease()  // Initialize native code, build pipeline, etc
    private external fun nativeFlush()
    private external fun nativePushFrame(data: ByteBuffer, len : Int, pts: Long, keyFrame: Boolean)
    private external fun nativePushConfig(data: ByteBuffer, lent : Int)


    private var queue = LinkedBlockingQueue<ByteBuffer>(20)
    private var mp4Stream = Mp4Stream(queue)

    init {
        //queue = LinkedBlockingQueue(4)

        try {
            nativeInit(config)
        } catch (e: Exception) {
            Log.e("VIDEOTEST", "Unable to initialize muxer")
            throw e
        }
    }

    fun release() {
        nativeRelease()
    }

    private fun onMuxedDataAvailable(data: ByteBuffer) {
        //Log.e("VIDEOTEST", "Pushing100")
        queue?.put(data)
    }

    fun pushConfig(data: ByteBuffer, len : Int) {
        nativePushConfig(data, len);
    }

    fun pushFrame(data: ByteBuffer, len : Int, pts: Long, keyFrame: Boolean) {
        if (keyFrame) {
            Log.e("VIDEOTEST", "Muxing keyframe")
        }
        nativePushFrame(data, len, pts, keyFrame);
    }

    fun flushData() {
        nativeFlush();
    }

    fun getMp4Stream() : Mp4Stream {
        return mp4Stream
    }

    fun remainingFreeBuffer(): Int {
        return queue.remainingCapacity()
    }

    companion object ClassInit {
        private external fun nativeClassInit(): Boolean  // Initialize native class: cache Method IDs for callbacks

        init {
            System.loadLibrary("ffmpegmp4muxer")
        }
    }

}


