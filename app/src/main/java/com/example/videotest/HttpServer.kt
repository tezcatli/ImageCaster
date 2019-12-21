package com.example.videotest

import android.os.Handler
import android.util.Log
import fi.iki.elonen.NanoHTTPD

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Semaphore

/*
class  HttpClientHandler: NanoHTTPD.ClientHandler {
    constructor( inputStream: InputStream, acceptSocket : Socket) : super()
}

 */


class HttpServer(var port: Int, var callbacks: HttpStreamingServiceCallbacks, var handler : Handler) : NanoHTTPD(port) {
    public var pictureStreamer: PictureStreamer? = null
    public var keySynchronization = Object()
    //public var connectionCounter = AtomicInteger(0)
    public var connectionCounter = Semaphore(1, true)
    public var imageUrl : URL? = null


    inner protected class HttpStreamSession : HTTPSession {

        constructor(
            tempFileManager: TempFileManager, inputStream: InputStream,
            outputStream: OutputStream, inetAddress: InetAddress
        ) :
                super(tempFileManager, inputStream, outputStream, inetAddress) {

            Log.e("HTTP Server", "Creating connection")
            //connectionCounter.incrementAndGet()
            connectionCounter.acquire()
        }

        override fun release() {
            //connectionCounter.decrementAndGet()
            connectionCounter.release()
            pictureStreamer?.release()
            synchronized(keySynchronization) {
                pictureStreamer = null
            }
            Log.e("HTTP Server", "Closing connection")
            super.release()
            handler.post( {
                callbacks.onDisconnected()
            })
        }
    }

    inner protected class HttpClientHandler : NanoHTTPD.ClientHandler {
        constructor(inputStream: InputStream, acceptSocket: Socket) : super(
            inputStream,
            acceptSocket
        ) {
            //Log.e("HTTP Server", "Creating connection")
        }

        override fun createSession(
            tempFileManager: TempFileManager,
            inputStream: InputStream,
            outputStream: OutputStream,
            inetAddress: InetAddress
        ): HTTPSession {
            return HttpStreamSession(tempFileManager, inputStream, outputStream, inetAddress)
        }

        /*
        override fun run() {
            try {
                connectionCounter.incrementAndGet()
                Log.e("HTTP Server", "Creating connection")
                super.run()
            } finally {

            }
        }
         */
    }

    override fun createClientHandler(
        finalAccept: Socket,
        inputStream: InputStream
    ): HttpClientHandler {
        return HttpClientHandler(inputStream, finalAccept)
    }


    override fun serve(session: IHTTPSession): Response {
        //Log.e("HTTP", "Received connection : ${connectionCounter.get()}")

        /*
        if (connectionCounter.get() > 1) {
            return newFixedLengthResponse(
                Response.Status.TOO_MANY_REQUESTS, "text/html", """
                    <!DOCTYPE html>
                    <html lang="en">
                    <body>
                    <!-- Already serving picture stream, try again -->
                    </body>
                    </html>"""
            )
        } else {
         */
            var ps = PictureStreamer.buildPictureStreamer(
                PictureStreamer.Config(
                    3840, 2160,
                    200000, 18
                )
            )
            synchronized(keySynchronization) {
                pictureStreamer = ps
                ps.handler.post({
                    ps.startStreaming()
                })
                ps.conditionVariable.block()
                handler.post( {
                    callbacks.onConnected()
                })
                return newChunkedResponse(
                    Response.Status.OK,
                    "video/H264",
                    pictureStreamer?.getMp4Stream()
                )
                // callbacks.onConnected()
            }

        }
    //}
    /*

    fun postToPictureStreamer(l : ()->Unit) {
        synchronized(keySynchronization) {


        }
    }
    */

    /*
    fun changeImage(url : URL) {
        synchronized(keySynchronization) {


        }
    }
    */


    init {
        start()
        Log.e("HTTP Server", "Running! Point your browers to http://localhost:${port}")
    }
}
