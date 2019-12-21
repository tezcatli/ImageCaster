package com.example.videotest

//import com.example.videotest.BitmapToVideoEncoder.IBitmapToVideoEncoderCallback

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.beust.klaxon.Klaxon
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.android.synthetic.main.activity_main.*
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class GooglePhotoAPIMediaItemsList(
    val mediaItems: List<GooglePhotoAPIMediaItem>,
    val nextPageToken: String
)

//
class GooglePhotoAPIMediaItem(val filename: String, val baseUrl: String, val mimeType: String)


class MainActivity : AppCompatActivity() {
    private var mStreamingStarted: Boolean = false
    private var server: HttpServer? = null

    //private lateinit var timeLapse: PictureStreamer
    //private lateinit var timeLapseHandler: Handler
    private lateinit var mUiHandler: Handler


    private var idx = 0

    private var mCastContext: CastContext? = null
    private var mediaRouteMenuItem: MenuItem? = null
    private var mCastSession: CastSession? = null
    private var mSessionManagerListener: SessionManagerListener<CastSession>? = null
    private lateinit var mAccountName: String
    private var mPhotoList: MutableList<GooglePhotoAPIMediaItem> =
        mutableListOf<GooglePhotoAPIMediaItem>()
    private var mCurrentPhotoIdx: Int = 0


    private val ACT_RC_ACCOUNT_PICK = 10

    private lateinit var mDetector: GestureDetectorCompat


    private val imagesList = listOf("test0.jpeg", "test1.png")

    private fun buildMediaInfo(): MediaInfo {

        Log.e("TRACE", "Cast picture");
        val pictureMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO)
        pictureMetadata.putString(MediaMetadata.KEY_TITLE, "To the moon")

        return MediaInfo.Builder("http://192.168.1.92:8080/")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("video/mp4")
            .setMetadata(pictureMetadata)
            .build()
    }

    private fun loadRemoteMedia() {
        if (mCastSession == null) {
            return
        }
        val remoteMediaClient = mCastSession?.getRemoteMediaClient() ?: return
        remoteMediaClient.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(buildMediaInfo())
                .setAutoplay(true)
                .build()
        )
    }

    private fun setupCastListener() {
        mSessionManagerListener = object : SessionManagerListener<CastSession> {

            override fun onSessionEnded(session: CastSession, error: Int) {
                onApplicationDisconnected()
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                onApplicationConnected(session)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                onApplicationDisconnected()
            }

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                onApplicationConnected(session)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                onApplicationDisconnected()
            }

            override fun onSessionStarting(session: CastSession) {}

            override fun onSessionEnding(session: CastSession) {}

            override fun onSessionResuming(session: CastSession, sessionId: String) {}

            override fun onSessionSuspended(session: CastSession, reason: Int) {}

            private fun onApplicationConnected(castSession: CastSession) {
                Log.e("CAST", "Application connected 2")

                mCastSession = castSession
                loadRemoteMedia()
            }

            private fun onApplicationDisconnected() {
                supportInvalidateOptionsMenu()
            }
        }
    }


    private inner class OnTokenAcquired : AccountManagerCallback<Bundle> {

        override fun run(result: AccountManagerFuture<Bundle>) {
            // Get the result of the operation from the AccountManagerFuture.
            val bundle: Bundle = result.getResult()

            // The token is a named value in the bundle. The name of the value
            // is stored in the constant AccountManager.KEY_AUTHTOKEN.
            val token: String = bundle.getString(AccountManager.KEY_AUTHTOKEN)

            Log.e("CAST4K", "Oauth ? : $token")

            var url = URL("https://photoslibrary.googleapis.com/v1/mediaItems:search")
            val conn = url.openConnection() as HttpURLConnection


            conn.apply {
                val jsonString : String = """
                    
                    {
  "filters": {
    "dateFilter": {
      "ranges": [
        {
          "startDate": {
            "year": 2013,
            "month": 9,
            "day": 0
          },
          "endDate": {
            "day": 0,
            "month": 10,
            "year": 2013
          }
        }
      ]
    }
  }
}
                """.trimMargin();
                setRequestProperty("Authorization", "OAuth $token")
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; utf-8")
                doOutput = true
                outputStream.write(jsonString.toByteArray())
            }


            val r =
                Klaxon().parse<GooglePhotoAPIMediaItemsList>(InputStreamReader(conn.inputStream))

            r?.mediaItems?.forEach() { item ->
                Log.e("CAST4K", item.filename)
                Log.e("CAST4K", item.mimeType)
                Log.e("CAST4K", item.baseUrl)
                mPhotoList.add(item)
            }

            displayPhoto()
        }
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        mDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ACT_RC_ACCOUNT_PICK) {
            val accountName =
                data?.extras?.get(AccountManager.KEY_ACCOUNT_NAME) as String ?: "NONE" as String
            Log.e("CAST", "Selected account: $accountName")

            val am: AccountManager = AccountManager.get(this)
            val options = Bundle()

            am.getAuthToken(
                Account(
                    accountName,
                    "com.google"
                ),                     // Account retrieved using getAccountsByType()
                "oauth2:https://www.googleapis.com/auth/photoslibrary.readonly",            // Auth scope
                options,                        // Authenticator-specific options
                this,                           // Your activity
                OnTokenAcquired(),              // Callback called when a token is successfully acquired
                Handler(OnError())              // Callback called if an error occurs
            )
        }
    }

    private class OnError : Handler.Callback {

        override fun handleMessage(p0: Message?): Boolean {
            Log.e("CAST4K", "Couille dans le potage")
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }
    }

    private fun displayPhoto() {
        if (mPhotoList.size > 0) {
            Log.e("VIDEOTEST", "Submitting new photo")
            var url = mPhotoList[mCurrentPhotoIdx].baseUrl + "=d"

            mService.changeImage(URL(url))
        }
    }

    private lateinit var mService: HttpStreamingService
    private var mBound: Boolean = false

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as HttpStreamingService.LocalBinder
            mService = binder.getService()
            mBound = true
            mService.startStreaming(object : HttpStreamingServiceCallbacks() {
                override fun onConnected() {
                    mStreamingStarted = true;
                    displayPhoto()
                    //cycleImage()
                }
            }, mUiHandler)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        mUiHandler = Handler()

        mDetector = GestureDetectorCompat(this, MyGestureListener())


        if (android.os.Build.VERSION.SDK_INT > 9) {
            val policy: StrictMode.ThreadPolicy =
                StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }

        Log.e("VIDEOTEST", getFilesDir().canonicalPath + "/4k.jpg")

        //requestEncodeImages()

        setupCastListener();
        mCastContext = CastContext.getSharedInstance(this);
        mCastSession = mCastContext?.getSessionManager()?.getCurrentCastSession();

        mCastContext?.getSessionManager()?.addSessionManagerListener(
            mSessionManagerListener, CastSession::class.java
        )


        val intent = AccountManager.newChooseAccountIntent(
            null, null, arrayOf("com.google"),
            false, null, null, null, null
        )
        startActivityForResult(intent, ACT_RC_ACCOUNT_PICK)


        Intent(this, HttpStreamingService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

    }


    private fun cycleImage() {
        Log.e("++VIDEOTEST++", "${imagesList[idx]}")

        mService.changeImage(
            URL(
                "file://" + getFilesDir().canonicalPath
                        + "/" + imagesList[idx]
            )
        )
        idx = (++idx).rem(imagesList.size)

        mUiHandler.postDelayed({
            cycleImage()
        }, 5000)
    }


    private fun requestEncodeImages() {

        // var inputStream = PipedInputStream();
        // var outputStream = PipedOutputStream(inputStream)

        /*
        timeLapse = PictureStreaming(
            PictureStreaming.Config(3840, 2160,
                1000000, 1))
        timeLapse.start()
        timeLapseHandler = Handler(timeLapse.getLooper())
        timeLapseHandler.post({
            timeLapse.startStreaming()
            cycleImage()
            server = HttpServer(8080, timeLapse.getMp4Stream())
        })

        */

    }

    private inner class MyGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(event: MotionEvent): Boolean {
            Log.e("CAST4K", "onDown: $event")
            return true
        }

        override fun onFling(
            event1: MotionEvent,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val cotan: Float = velocityX / abs(velocityY)

            when {
                (abs(cotan) > 2) and (cotan > 0) -> {
                    if (mCurrentPhotoIdx < (mPhotoList.size - 1)) {
                        mCurrentPhotoIdx++
                        displayPhoto()
                    }
                }
                (abs(cotan) > 2) and (cotan < 0) -> {
                    if (mCurrentPhotoIdx > 0) {
                        mCurrentPhotoIdx--
                        displayPhoto()
                    }
                }
            }

            return true
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_main, menu)
        mediaRouteMenuItem = CastButtonFactory.setUpMediaRouteButton(
            getApplicationContext(),
            menu,
            R.id.media_route_menu_item
        );

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }
}
