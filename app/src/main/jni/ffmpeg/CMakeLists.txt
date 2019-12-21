cmake_minimum_required(VERSION 3.4.1)

add_library( # Specifies the name of the library.
             ffmpegmp4muxer

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             Mp4Muxer.c )

find_library(
        log-lib
        log
)

add_library(
        libavcodec
        SHARED
        IMPORTED)

set_target_properties(
        libavcodec
        PROPERTIES IMPORTED_LOCATION /home/user/ffmpeg/output/lib/${ANDROID_ABI}/libavcodec.so)

add_library(
        libavformat
        SHARED
        IMPORTED)

set_target_properties(
        libavformat
        PROPERTIES IMPORTED_LOCATION /home/user/ffmpeg/output/lib/${ANDROID_ABI}/libavformat.so)

add_library(
        libavutil
        SHARED
        IMPORTED)

set_target_properties(
        libavutil
        PROPERTIES IMPORTED_LOCATION /home/user/ffmpeg/output/lib/${ANDROID_ABI}/libavutil.so)

include_directories(
        /home/user/ffmpeg/output/include/
)

target_link_libraries( # Specifies the target library.
        ffmpegmp4muxer

        # Links the log library to the target library.
        ${log-lib} libavformat libavcodec libavutil )