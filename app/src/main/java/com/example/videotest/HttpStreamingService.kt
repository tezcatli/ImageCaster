package com.example.videotest

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import java.net.URL

open class HttpStreamingServiceCallbacks {
    open fun onConnected()  {

    }

    open fun onDisconnected() {

    }
}

class HttpStreamingService : Service() {
    // Binder given to clients
    private val binder = LocalBinder()
    private lateinit var timeLapse: PictureStreamer
    private lateinit var timeLapseHandler: Handler
    private lateinit var httpServer: HttpServer
    private lateinit var callbacks: HttpStreamingServiceCallbacks
    private var started : Boolean = false

    // Random number generator

    fun startStreaming(callbacks : HttpStreamingServiceCallbacks, handler : Handler) {
        /*
        timeLapse = PictureStreaming(
            PictureStreaming.Config(3840, 2160,
                1000000, 1))
        timeLapse.start()
        timeLapseHandler = Handler(timeLapse.getLooper())
        timeLapseHandler.post({
            //timeLapse.startStreaming()
            //cycleImage()
            // Factory
            httpServer = HttpServer(8080, timeLapse.getMp4Stream())
        })
        */
        started = true
        this.callbacks = callbacks
        httpServer = HttpServer(8080, callbacks, handler)
    }

    fun changeImage(url: URL) {
        synchronized(httpServer.keySynchronization) {
            httpServer.pictureStreamer?.handler?.post({
                httpServer.pictureStreamer?.setupImage(url)
            })
        }
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): HttpStreamingService = this@HttpStreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
