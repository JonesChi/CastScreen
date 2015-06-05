package com.yschi.castscreen;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class CastService extends Service {
    private final String TAG = "CastService";
    private final int NT_ID_CASTING = 0;
    private Handler mHandler = new Handler(new ServiceHandlerCallback());
    private Messenger mMessenger = new Messenger(mHandler);
    private ArrayList<Messenger> mClients = new ArrayList<Messenger>();
    private IntentFilter mBroadcastIntentFilter;

    private static final String HTTP_MESSAGE_TEMPLATE = "POST /api/v1/h264 HTTP/1.1\r\n" +
                                                        "Connection: close\r\n" +
                                                        "X-WIDTH: %1$d\r\n" +
                                                        "X-HEIGHT: %2$d\r\n" +
                                                        "\r\n";

    // 1280x720@25
    private static final byte[] H264_PREDEFINED_HEADER_1280x720 = {
            (byte)0x21, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
            (byte)0x67, (byte)0x42, (byte)0x80, (byte)0x20, (byte)0xda, (byte)0x01, (byte)0x40, (byte)0x16,
            (byte)0xe8, (byte)0x06, (byte)0xd0, (byte)0xa1, (byte)0x35, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x01, (byte)0x68, (byte)0xce, (byte)0x06, (byte)0xe2, (byte)0x32, (byte)0x24, (byte)0x00,
            (byte)0x00, (byte)0x7a, (byte)0x83, (byte)0x3d, (byte)0xae, (byte)0x37, (byte)0x00, (byte)0x00};

    // 800x480@25
    private static final byte[] H264_PREDEFINED_HEADER_800x480 = {
            (byte)0x21, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
            (byte)0x67, (byte)0x42, (byte)0x80, (byte)0x20, (byte)0xda, (byte)0x03, (byte)0x20, (byte)0xf6,
            (byte)0x80, (byte)0x6d, (byte)0x0a, (byte)0x13, (byte)0x50, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x01, (byte)0x68, (byte)0xce, (byte)0x06, (byte)0xe2, (byte)0x32, (byte)0x24, (byte)0x00,
            (byte)0x00, (byte)0x7a, (byte)0x83, (byte)0x3d, (byte)0xae, (byte)0x37, (byte)0x00, (byte)0x00};

    private MediaProjectionManager mMediaProjectionManager;
    private String mReceiverIp;
    private int mResultCode;
    private Intent mResultData;
    private int mSelectedWidth;
    private int mSelectedHeight;
    private int mSelectedDpi;
    private int mSelectedBitrate;
    //private boolean mMuxerStarted = false;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
    //private MediaMuxer mMuxer;
    private MediaCodec mVideoEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    //private int mTrackIndex = -1;
    private Socket mSocket;
    private OutputStream mSocketOutputStream;
    private BufferedOutputStream mFileOutputStream;
    private Handler mDrainHandler = new Handler();
    private Runnable mDrainEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainEncoder();
        }
    };

    private class ServiceHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            Log.d(TAG, "Handler got event, what: " + msg.what);
            switch (msg.what) {
                case Common.MSG_REGISTER_CLIENT: {
                    mClients.add(msg.replyTo);
                    break;
                }
                case Common.MSG_UNREGISTER_CLIENT: {
                    mClients.remove(msg.replyTo);
                    break;
                }
                case Common.MSG_STOP_CAST: {
                    stopScreenCapture();
                    closeSocket();
                    stopSelf();
                }
            }
            return false;
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Service receive broadcast action: " + action);
            if (action == null) {
                return;
            }
            if (Common.ACTION_STOP_CAST.equals(action)) {
                stopScreenCapture();
                closeSocket();
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mBroadcastIntentFilter = new IntentFilter();
        mBroadcastIntentFilter.addAction(Common.ACTION_STOP_CAST);
        registerReceiver(mBroadcastReceiver, mBroadcastIntentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroy service");
        stopScreenCapture();
        closeSocket();
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        mReceiverIp = intent.getStringExtra(Common.EXTRA_RECEIVER_IP);
        mResultCode = intent.getIntExtra(Common.EXTRA_RESULT_CODE, -1);
        mResultData = intent.getParcelableExtra(Common.EXTRA_RESULT_DATA);
        if (mReceiverIp == null || mResultCode != Activity.RESULT_OK || mResultData == null) {
            Log.e(TAG, "Failed to start service, ip: " + mReceiverIp + ", mResultCode: " + mResultCode + ", mResultData: " + mResultData);
            return START_NOT_STICKY;
        }
        mSelectedWidth = intent.getIntExtra(Common.EXTRA_SCREEN_WIDTH, Common.DEFAULT_SCREEN_WIDTH);
        mSelectedHeight = intent.getIntExtra(Common.EXTRA_SCREEN_HEIGHT, Common.DEFAULT_SCREEN_HEIGHT);
        mSelectedDpi = intent.getIntExtra(Common.EXTRA_SCREEN_DPI, Common.DEFAULT_SCREEN_DPI);
        mSelectedBitrate = intent.getIntExtra(Common.EXTRA_VIDEO_BITRATE, Common.DEFAULT_VIDEO_BITRATE);
        if (!createSocket()) {
            Log.e(TAG, "Failed to create socket to receiver, ip: " + mReceiverIp);
            return START_NOT_STICKY;
        }
        if (!startScreenCapture()) {
            Log.e(TAG, "Failed to start capture screen");
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    private void showNotification() {
        final Intent notificationIntent = new Intent(Common.ACTION_STOP_CAST);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.casting_screen))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), notificationPendingIntent);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NT_ID_CASTING, builder.build());
    }

    private void dismissNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NT_ID_CASTING);
    }

    private boolean startScreenCapture() {
        if (mResultCode != 0 && mResultData != null) {
            setUpMediaProjection();
            startRecording();
            showNotification();
            return true;
        }
        return false;
    }

    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }

    private void startRecording() {
        Log.d(TAG, "startRecording");
        prepareVideoEncoder();

        //try {
        //    mMuxer = new MediaMuxer("/sdcard/video.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        //} catch (IOException ioe) {
        //    throw new RuntimeException("MediaMuxer creation failed", ioe);
        //}

        // Start the video input.
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Recording Display", mSelectedWidth,
                mSelectedHeight, mSelectedDpi, 0 /* flags */, mInputSurface,
                null /* callback */, null /* handler */);

        // Start the encoders
        drainEncoder();
    }

    private void prepareVideoEncoder() {
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(Common.DEFAULT_VIDEO_MIME_TYPE, mSelectedWidth, mSelectedHeight);
        int frameRate = Common.DEFAULT_VIDEO_FPS;

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mSelectedBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(Common.DEFAULT_VIDEO_MIME_TYPE);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial encoder, e: " + e);
            releaseEncoders();
        }
    }

    private boolean drainEncoder() {
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);
        while (true) {
            int bufferIndex = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, 0);

            if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // nothing available yet
                break;
            } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                //if (mTrackIndex >= 0) {
                //    throw new RuntimeException("format changed twice");
                //}
                //mTrackIndex = mMuxer.addTrack(mVideoEncoder.getOutputFormat());
                //if (!mMuxerStarted && mTrackIndex >= 0) {
                //    mMuxer.start();
                //    mMuxerStarted = true;
                //}
            } else if (bufferIndex < 0) {
                // not sure what's going on, ignore it
            } else {
                ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(bufferIndex);
                if (encodedData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
                }

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mVideoBufferInfo.size = 0;
                }

                //Log.d(TAG, "Video buffer offset: " + mVideoBufferInfo.offset + ", size: " + mVideoBufferInfo.size);
                if (mVideoBufferInfo.size != 0) {
                    encodedData.position(mVideoBufferInfo.offset);
                    encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);
                    if (mSocketOutputStream != null) {
                        try {
                            byte[] b = new byte[encodedData.remaining()];
                            encodedData.get(b);
                            mSocketOutputStream.write(b);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    /*
                    if (mMuxerStarted) {
                        encodedData.position(mVideoBufferInfo.offset);
                        encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);
                        try {
                            if (mSocketOutputStream != null) {
                                byte[] b = new byte[encodedData.remaining()];
                                encodedData.get(b);
                                mSocketOutputStream.write(b);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        mMuxer.writeSampleData(mTrackIndex, encodedData, mVideoBufferInfo);
                    } else {
                        // muxer not started
                    }
                    */
                }

                mVideoEncoder.releaseOutputBuffer(bufferIndex, false);

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }

        mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
        return false;
    }

    private void stopScreenCapture() {
        dismissNotification();
        releaseEncoders();
        closeSocket();
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.release();
        mVirtualDisplay = null;
    }

    private void releaseEncoders() {
        mDrainHandler.removeCallbacks(mDrainEncoderRunnable);
        /*
        if (mMuxer != null) {
            if (mMuxerStarted) {
                mMuxer.stop();
            }
            mMuxer.release();
            mMuxer = null;
            mMuxerStarted = false;
        }
        */
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        mResultCode = 0;
        mResultData = null;
        mVideoBufferInfo = null;
        //mTrackIndex = -1;
    }

    private boolean createSocket() {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress serverAddr = InetAddress.getByName(mReceiverIp);
                    mSocket = new Socket(serverAddr, Common.VIEWER_PORT);
                    mSocketOutputStream = mSocket.getOutputStream();
                    OutputStreamWriter osw = new OutputStreamWriter(mSocketOutputStream);
                    osw.write(String.format(HTTP_MESSAGE_TEMPLATE, mSelectedWidth, mSelectedHeight));
                    osw.flush();
                    if (mSelectedWidth == 1280 && mSelectedHeight == 720) {
                        mSocketOutputStream.write(H264_PREDEFINED_HEADER_1280x720);
                    } else if (mSelectedWidth == 800 && mSelectedHeight == 480) {
                        mSocketOutputStream.write(H264_PREDEFINED_HEADER_800x480);
                    } else {
                        Log.e(TAG, "Unknown width: " + mSelectedWidth + ", height: " + mSelectedHeight);
                        mSocketOutputStream.close();
                        mSocket.close();
                        mSocket = null;
                        mSocketOutputStream = null;
                    }
                    return;
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mSocket = null;
                mSocketOutputStream = null;
            }
        });
        th.start();
        try {
            th.join();
            if (mSocket != null && mSocketOutputStream != null) {
                return true;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void closeSocket() {
        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mSocket = null;
        mSocketOutputStream = null;
    }
}
