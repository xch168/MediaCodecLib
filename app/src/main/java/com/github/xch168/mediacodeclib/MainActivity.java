package com.github.xch168.mediacodeclib;

import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.LinearLayout;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private TextureView mMovieView;
    private SurfaceTexture mSurfaceTexture;

    private MediaExtractor mMediaExtractor;
    private MediaCodec mDecoder;
    private MediaFormat mVideoFormat;

    private String mMimeType;
    private int mVideoTrackIndex = -1;
    private int mWidth = 720;
    private int mHeight = 1280;

    private long mPrevMonoUsec = 0;
    private long mPrevPresentUsec = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMovieView = findViewById(R.id.movie_display);
        mMovieView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                // 作为巳害怕解码器的数据输出。NOTE：在SurfaceTexture available之后开始解码
                mSurfaceTexture = surface;
                decode();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });

        initExtractor();
    }

    /**
     * 通过MediaExtractor从视频文件中获取视频相关参数
     */
    private void initExtractor() {
        try {
            mMediaExtractor = new MediaExtractor();
            AssetFileDescriptor testFile = getAssets().openFd("testfile.mp4");
            mMediaExtractor.setDataSource(testFile.getFileDescriptor(), testFile.getStartOffset(), testFile.getLength());
            int trackCount = mMediaExtractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = mMediaExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/avc")) {
                    mVideoFormat = format;
                    mVideoTrackIndex = i;
                    mMimeType = mime;
                    break;
                }
            }
            mWidth = mVideoFormat.getInteger(MediaFormat.KEY_WIDTH);
            mHeight = mVideoFormat.getInteger(MediaFormat.KEY_HEIGHT);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(mWidth, mHeight);
                    mMovieView.setLayoutParams(params);
                }
            });
        } catch (IOException e) {
            mMediaExtractor.release();
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void decode() {
        try {
            // 选择视频轨道
            mMediaExtractor.selectTrack(mVideoTrackIndex);
            // 根据视频格式，创建对应的解码器
            mDecoder = MediaCodec.createDecoderByType(mMimeType);
            mDecoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    // 从解码器中拿到输入buffer，让用户填充数据
                    ByteBuffer decoderInputBuffer = mDecoder.getInputBuffer(index);
                    boolean videoExtractorDone = false;
                    while (!videoExtractorDone) {
                        // 从视频中读取数据
                        int size = mMediaExtractor.readSampleData(decoderInputBuffer, 0);
                        long presentationTime = mMediaExtractor.getSampleTime();
                        // 如果读取到数据，把buffer交回给解码器
                        if (size >= 0) {
                            mDecoder.queueInputBuffer(index, 0, size, presentationTime, mMediaExtractor.getSampleFlags());
                        }
                        videoExtractorDone = !mMediaExtractor.advance();
                        if (videoExtractorDone) {
                            // 如果取下一帧数据失败的时候，也把buffer仍回去，带上end of stream标记，告知解码器，视频数据已经解析完
                            mDecoder.queueInputBuffer(index, 0 ,0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                        if (size >= 0) {
                            break;
                        }
                    }
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codec.releaseOutputBuffer(index, false);
                        return;
                    }
                    waitToDisplay(info);
                    // 将数据输出到SurfaceTexture
                    codec.releaseOutputBuffer(index, true);
                    // 如果视频数据已经读到结尾，则调用MediaExtractor的seekTo,跳转到视频开头，并且重置解码器
                    boolean reset = ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0);
                    if (reset) {
                        mPrevMonoUsec = 0;
                        mPrevPresentUsec = 0;
                        mMediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        mDecoder.flush();
                        mDecoder.start();
                    }
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    codec.reset();
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    mVideoFormat = format;
                }
            });
            // 设置渲染的颜色格式为Surface
            mVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // 设置解码数据到指定的Surface上
            mDecoder.configure(mVideoFormat, new Surface(mSurfaceTexture), null, 0);
            mDecoder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void waitToDisplay(MediaCodec.BufferInfo info) {
        if (mPrevMonoUsec == 0) {
            mPrevMonoUsec = System.nanoTime() / 1000;
            mPrevPresentUsec = info.presentationTimeUs;
        } else {
            // 帧间隔 = 当前帧显示的时间 - 上一帧显示时间
            long delta = info.presentationTimeUs - mPrevPresentUsec;
            if (delta < 0) {
                delta = 0;
            }
            // 预计当前帧显示时间 = 上一帧显示时间 + 帧间隔
            long desiredUsec = mPrevMonoUsec + delta;
            long nowUsec = System.nanoTime() / 1000;
            while (nowUsec < (desiredUsec - 100)) {
                long sleepTimeUsec = desiredUsec - nowUsec;
                if (sleepTimeUsec > 500000) {
                    sleepTimeUsec = 500000;
                }
                if (sleepTimeUsec > 0) {
                    try {
                        Thread.sleep(sleepTimeUsec / 1000, (int) ((sleepTimeUsec % 1000) * 1000));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                nowUsec = System.nanoTime() / 1000;
            }
            mPrevMonoUsec += delta;
            mPrevPresentUsec += delta;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDecoder != null) {
            mDecoder.stop();
            mDecoder.release();
        }
    }
}
