package com.example.videotest


import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import android.content.Context;
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.SessionProvider

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context : Context) : CastOptions {
        return CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        //return CastOptions.Builder().setReceiverApplicationId(context.getString(R.string.app_cast_id))
            .build();
    }
    override fun getAdditionalSessionProviders(context : Context) : List<SessionProvider>? {
        return null;
    }
}

