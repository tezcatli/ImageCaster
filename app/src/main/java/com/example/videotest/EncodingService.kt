package com.example.videotest

import android.app.IntentService
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.InvalidParameterException

/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
class EncodingService: IntentService(TAG) {

    override fun onHandleIntent(p0: Intent?) {
        Log.e("VIDEOTEST", "Calling encoder")

        when (p0?.action) {
            ACTION_ENCODE_IMAGES -> encodeImages(p0)
        }
    }

    private fun encodeImages(intent: Intent) {
        val imageUris = intent.getParcelableArrayListExtra<Uri>(KEY_IMAGES)
        val outPath = intent.getStringExtra(KEY_OUT_PATH)

        // If there is audio present, we encode video into a temporary file first. Later we can mux the
        // video and audio into the final outPath video file
        var videoPath = if (intent.hasExtra(KEY_AUDIO)) cacheDir.absolutePath + "/tmp.mp4" else outPath

        val videoFile = File(videoPath)
        if (videoFile.exists())
            videoFile.delete()

        // Generate video from provided image uris

/*
        TimeLapseEncoder().encode(this, videoPath, imageUris)
*/
        // Notify MainActivity that we're done
        // TODO: add error message and change result code in case of error
        val pi = intent.getParcelableExtra<PendingIntent>(KEY_RESULT_INTENT)
        pi.send()
    }

/*
    public fun encodeImages2(imageUris : List<Uri>, outPath : String) {
        //val imageUris = intent.getParcelableArrayListExtra<Uri>(KEY_IMAGES)
        //val outPath = intent.getStringExtra(KEY_OUT_PATH)

        // If there is audio present, we encode video into a temporary file first. Later we can mux the
        // video and audio into the final outPath video file
        var videoPath = outPath

        val videoFile = File(videoPath)
        if (videoFile.exists())
            videoFile.delete()

        // Generate video from provided image uris


        TimeLapseEncoder().encode(videoPath, imageUris, contentResolver)

        // Notify MainActivity that we're done
        // TODO: add error message and change result code in case of error
        //val pi = intent.getParcelableExtra<PendingIntent>(KEY_RESULT_INTENT)
        //pi.send()
    }

 */

    private fun getVideoDurationMillis(videoFilePath: String): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(videoFilePath)

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)

            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                return format.getLong(MediaFormat.KEY_DURATION)/1000
            }
        }

        throw InvalidParameterException("No video track found in file")
    }


    companion object {

        val TAG = this::class.java.simpleName

        const val ACTION_ENCODE_IMAGES = "eu.sisik.vidproc.action.ENCODE_IMAGES"

        const val KEY_IMAGES = "eu.sisik.vidproc.key.IMAGES"

        const val KEY_AUDIO = "eu.sisik.vidproc.key.AUDIO"

        const val KEY_OUT_PATH = "eu.sisik.vidproc.key.OUT_PATH"

        const val KEY_RESULT_INTENT = "eu.sisik.vidproc.key.RESULT_INTENT"
    }
}