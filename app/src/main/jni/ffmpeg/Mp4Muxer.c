#if 1

#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <malloc.h>
#include <unistd.h>

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/rational.h"
#include "libavutil/error.h"

#define FFTAG "FFMUXER"

#define GLIB_SIZEOF_VOID_P 8

/*
 * These macros provide a way to store the native pointer to CustomData, which might be 32 or 64 bits, into
 * a jlong, which is always 64 bits, without warnings.
 */
#if GLIB_SIZEOF_VOID_P == 8
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(*env)->GetLongField (env, thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)data)
#else
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(jint)(*env)->GetLongField (env, thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)(jint)data)
#endif


static int callback_write_packet(void *opaque, uint8_t *buf, int buf_size);

/* Structure to contain all our information, so we can pass it to callbacks */
typedef struct _CustomData {
    jobject app;                  /* Application instance, used to call its methods. A global reference is kept. */
    int initialized;
    AVOutputFormat *outputFormat;
    AVFormatContext *outputFormatCtx;
    AVCodec* outCodec;
    AVStream* outputStream;
    AVCodecContext* outputCodecCtx;
    AVRational timebase;
    AVPacket * packet;
    AVIOContext * avIoCtx;
    unsigned char * avIoCtxBuffer;
    AVDictionary* outputOpts;
    JNIEnv *env;
    //jobject thiz;
} CustomData;

/*
typedef struct IoContext {
    JNIEnv *env;
    jobject thiz;
};
 */

/* These global variables cache values which are not changing during execution */

static JavaVM *java_vm;
static jfieldID custom_data_field_id;
static jmethodID on_muxed_data_available_method_id;


/*
 * Java Bindings
 */

/* Instruct the native code to create its internal data structure, pipeline and thread */
static void
native_init(JNIEnv *env, jobject thiz, jobject jconfig) {
    AVRational * time_base;

    CustomData *data = calloc(1, sizeof(CustomData));
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, data);

    __android_log_print(ANDROID_LOG_INFO, FFTAG, "Created CustomData at %p", data);
    data->app = (*env)->NewGlobalRef(env, thiz);
    __android_log_print(ANDROID_LOG_INFO, FFTAG, "Created GlobalRef for app object at %p",
                        data->app);

    jclass cls;
    cls = (*env)->GetObjectClass(env, jconfig);

    if (!jconfig) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                "Unable to get class TimeLapseEncode.Config");
    }



    /**** initialize ffmpeg library *****/

    data->outputFormat = av_guess_format("mp4", 0,NULL);
    if(!data->outputFormat) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "ERROR av guess format");
    }

    if (avformat_alloc_output_context2(&data->outputFormatCtx, data->outputFormat, NULL, NULL) < 0) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Unable to allocate ffmpeg context");
    }

    data->outCodec = avcodec_find_decoder(AV_CODEC_ID_H264);

    if( !data->outCodec ){
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Unable to find h264 codec");
    }


    data->env = env;
    //data->thiz = (*env)->NewGlobalRef (env, thiz);

    data->avIoCtxBuffer = av_malloc(4096*16);
    data->avIoCtx = avio_alloc_context(data->avIoCtxBuffer, 4096*16,
                                       1, data, NULL, &callback_write_packet, NULL);
    if (!data->avIoCtx) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Unable to alloc output IO context");
    }

    data->outputFormatCtx->pb = data->avIoCtx;
    data->outputFormatCtx->flags = AVFMT_FLAG_CUSTOM_IO;

    data->outputStream = avformat_new_stream(data->outputFormatCtx,data->outCodec);
    if(!data->outputStream){
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Unable to declare new stream");
    }

    data->outputCodecCtx = avcodec_alloc_context3(data->outCodec);
    if(!data->outputCodecCtx){
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Unable to alloc output codec context");
    }

    data->outputStream->codecpar->codec_id = AV_CODEC_ID_H264;
    data->outputStream->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
    data->outputStream->codecpar->format = AV_PIX_FMT_YUV420P;
    data->timebase.num = 1;

    jfieldID id;

    id = (*env)->GetFieldID(env, cls, "width", "I");
    if (!id) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                         "Unable to get field : width");
    }
    data->outputStream->codecpar->width = (*env)->GetIntField(env, jconfig, id);

    id = (*env)->GetFieldID(env, cls, "height", "I");
    if (!id) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                         "Unable to get field :  height");
    }
    data->outputStream->codecpar->height = (*env)->GetIntField(env, jconfig, id);

    id = (*env)->GetFieldID(env, cls, "bitrate", "I");
    if (!id) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                         "Unable to get field :  bitrate");
    }
    data->outputStream->codecpar->bit_rate = (*env)->GetIntField(env, jconfig, id);


    id = (*env)->GetFieldID(env, cls, "framerate", "I");
    if (!id) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                         "Unable to get field :  framerate");
    }
    data->timebase.den = (*env)->GetIntField(env, jconfig, id);

    data->outputStream->time_base = data->timebase;
    data->outputCodecCtx->time_base = data->timebase;

    av_dict_set(&data->outputOpts, "movflags", "frag_keyframe+separate_moof+negative_cts_offsets+frag_custom", 0);
    //av_dict_set(&data->outputOpts, "frag_duration", "1000000", 0);
    av_dict_set(&data->outputOpts, "tune", "zerolatency", 0);

    if (avcodec_open2(data->outputCodecCtx, data->outCodec, &data->outputOpts) < 0) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Error avcodec_open 2");
    }

    data->packet = av_packet_alloc();

}

/* Quit the main loop, remove the native thread and free resources */
static void
native_release(JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data)
        return;

    av_dict_free(&data->outputOpts);
    avcodec_free_context(&data->outputCodecCtx);
    //avcodec_close(data->outputCodecCtx);
    av_free(data->avIoCtxBuffer);
    avformat_free_context(data->outputFormatCtx);



    __android_log_print(ANDROID_LOG_INFO, FFTAG, "Deleting GlobalRef for app object at %p",
                        data->app);
    (*env)->DeleteGlobalRef(env, data->app);
    //(*env)->DeleteGlobalRef (env, thiz);

    __android_log_print(ANDROID_LOG_INFO, FFTAG, "Done finalizing");
}

/* Static class initializer: retrieve method and field IDs */
static jboolean
native_class_init(JNIEnv *env, jclass klass) {
    custom_data_field_id =
            (*env)->GetFieldID(env, klass, "native_custom_data", "J");
    on_muxed_data_available_method_id =
            (*env)->GetMethodID(env, klass, "onMuxedDataAvailable", "(Ljava/nio/ByteBuffer;)V");

    if (!custom_data_field_id
        || !on_muxed_data_available_method_id) {
        /* We emit this message through the Android log instead of the GStreamer log because the later
         * has not been initialized yet.
         */
        __android_log_print(ANDROID_LOG_ERROR, FFTAG,
                            "The calling class does not implement all necessary interface methods");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}



static int callback_write_packet(void *opaque, uint8_t *buf, int buf_size) {
    CustomData *data = opaque;
    void * addr;

    addr = malloc(buf_size);
    if (!addr) {
        __android_log_print(ANDROID_LOG_ERROR, FFTAG, "Unable to allocate ByteArray of size %i", buf_size);
        return -1;
    }

    jobject buffer = (*data->env)->NewDirectByteBuffer(data->env, addr, buf_size);
    if (!buffer) {
        __android_log_print(ANDROID_LOG_ERROR, FFTAG, "Unable to allocate ByteArray of size %i", buf_size);
        return -1;
    }
    memcpy(addr, buf, buf_size);
    (*data->env)->CallVoidMethod (data->env, data->app, on_muxed_data_available_method_id, buffer);

    (*data->env)->DeleteLocalRef(data->env, buffer);




    return buf_size;
}


static void native_flush(JNIEnv *env, jobject thiz) {
    int ret;
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
/*
    if ((ret = av_write_trailer(data->outputFormatCtx)) < 0) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Error av_write_frame");
    }
    */

}

static int interleave_packet(AVFormatContext *s, AVPacket *out, AVPacket *in, int flush);
static int write_packet(AVFormatContext *s, AVPacket *pkt);



static void native_push_frame(JNIEnv *env, jobject thiz, jobject buffer, jint len, jlong timestamp,
                              jboolean keyframe) {
    jsize wlen;
    int ret;

    AVRational usec = {1, 1000000};

    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);

    jbyte * ptr = (*env)->GetDirectBufferAddress(env, buffer);
    //jsize len = (*env)->GetDirectBufferCapacity(env, buffer);

    //av_packet_from_data(data->packet, ptr, len);
    data->packet->buf = 0;
    data->packet->data = (uint8_t*)ptr;
    data->packet->size = len;

    if (keyframe)
        data->packet->flags = AV_PKT_FLAG_KEY;
    else
        data->packet->flags = 0;

    data->packet->pts = av_rescale_q(timestamp, usec, data->outputStream->time_base);
    data->packet->dts = AV_NOPTS_VALUE;

    if ((ret = av_write_frame(data->outputFormatCtx, data->packet)) < 0) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Error av_write_frame");
    }

    if ((ret = av_write_frame(data->outputFormatCtx, 0)) < 0) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Error av_write_frame");
    }

}

static void native_push_config(JNIEnv *env, jobject thiz, jobject buffer, jint len) {

    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);

    jbyte * ptr = (*env)->GetDirectBufferAddress(env, buffer);
    //jsize len = (*env)->GetDirectBufferCapacity(env, buffer);

    data->outputStream->codecpar->extradata = malloc(len);

    if (! data->outputStream->codecpar->extradata) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Unable to alloc extradata");
    }

    data->outputStream->codecpar->extradata_size = len;
    memcpy(data->outputStream->codecpar->extradata, ptr, len);

    int error;
    if((error = avformat_write_header(data->outputFormatCtx , &data->outputOpts)) < 0){
        static char errorstr[64];
        av_strerror(error, errorstr, sizeof(errorstr));
        errorstr[sizeof(errorstr) - 1] = 0;
        __android_log_print(ANDROID_LOG_INFO, FFTAG, "error : %s", errorstr);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Error avformat_write_header");
    }

    __android_log_print(ANDROID_LOG_ERROR, FFTAG, "Pushed %i bytes of config", len);

}

/* List of implemented native methods */
static JNINativeMethod mp4muxer_native_methods[] = {
        {"nativeFlush",     "()V",   (void *) native_flush},
        {"nativePushFrame",     "(Ljava/nio/ByteBuffer;IJZ)V", (void *) native_push_frame},
        {"nativePushConfig",     "(Ljava/nio/ByteBuffer;I)V", (void *) native_push_config},
        {"nativeInit",      "(Lcom/example/videotest/PictureStreamer$Config;)V",    (void *) native_init},
        {"nativeRelease",  "()V",    (void *) native_release}
//        {"nativeClassInit", "()Z",    (void *) native_class_init}
};


static JNINativeMethod mp4muxer_classinit_native_methods[] = {
        {"nativeClassInit", "()Z",    (void *) native_class_init}
};



/* Library initializer */
JNIEXPORT
jint
JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;

    java_vm = vm;

    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_4) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "tutorial-2",
                            "Could not retrieve JNIEnv");
        return 0;
    }
    jclass klass = (*env)->FindClass(env,
                                     "com/example/videotest/Mp4Muxer");
    (*env)->RegisterNatives(env, klass, mp4muxer_native_methods,
                            (sizeof(mp4muxer_native_methods) / sizeof((mp4muxer_native_methods)[0])));

    /*
    klass = (*env)->FindClass(env,
                                     "com/example/videotest/Mp4MuxerK/static");
    (*env)->RegisterNatives(env, klass, mp4muxer_classinit_native_methods,
                            (sizeof(mp4muxer_classinit_native_methods) / sizeof((mp4muxer_classinit_native_methods)[0])));
                            */
    native_class_init(env, klass);

    return JNI_VERSION_1_4;
}


#endif