package com.yschi.castscreen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;


public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private static final String PREF_COMMON = "common";
    private static final String PREF_KEY_RECEIVER = "receiver";

    private static final String DISCOVER_MESSAGE = "hello";
    private static final int DISCOVER_PORT = 53515;

    private static final int VIEWER_PORT = 53515;

    private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int VIDEO_FPS = 30;
    private static final int VIRTUAL_DISPLAY_DENSITY = 320;
    private static final int VIRTUAL_DISPLAY_WIDTH = VIDEO_WIDTH;
    private static final int VIRTUAL_DISPLAY_HEIGHT = VIDEO_HEIGHT;

    private static final byte[] H264_PREDEFINED_HEADER = {
            (byte)0x21, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01,
            (byte)0x67, (byte)0x42, (byte)0x80, (byte)0x20, (byte)0xda, (byte)0x01, (byte)0x40, (byte)0x16,
            (byte)0xe8, (byte)0x06, (byte)0xd0, (byte)0xa1, (byte)0x35, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x01, (byte)0x68, (byte)0xce, (byte)0x06, (byte)0xe2, (byte)0x32, (byte)0x24, (byte)0x00,
            (byte)0x00, (byte)0x7a, (byte)0x83, (byte)0x3d, (byte)0xae, (byte)0x37, (byte)0x00, (byte)0x00};

    private static final int REQUEST_MEDIA_PROJECTION = 100;
    private static final String STATE_RESULT_CODE = "result_code";
    private static final String STATE_RESULT_DATA = "result_data";

    private Context mContext;
    private Handler mHandler = new Handler();
    private TextView mReceiverTextView;
    private ListView mDiscoverListView;
    private ArrayAdapter<String> mDiscoverAdapter;
    private HashMap<String, String> mDiscoverdMap;
    private String mReceiverIp = "";
    private MediaProjectionManager mMediaProjectionManager;
    private int mResultCode;
    private Intent mResultData;
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
    private DiscoveryTask mDiscoveryTask;

    //private final Handler mDrainHandler = new Handler(Looper.getMainLooper());
    private Handler mDrainHandler;
    private Runnable mDrainEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            drainEncoder();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            mResultCode = savedInstanceState.getInt(STATE_RESULT_CODE);
            mResultData = savedInstanceState.getParcelable(STATE_RESULT_DATA);
        }

        mContext = this;
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        mDiscoverdMap = new HashMap<>();
        mDiscoverListView = (ListView) findViewById(R.id.discover_listview);
        mDiscoverAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1);
        mDiscoverAdapter.addAll(mDiscoverdMap.keySet());
        mDiscoverListView.setAdapter(mDiscoverAdapter);
        mDiscoverListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                String name = mDiscoverAdapter.getItem(i);
                String ip = mDiscoverdMap.get(name);
                Log.d(TAG, "Select receiver name: " + name + ", ip: " + ip);
                mReceiverIp = ip;
                updateReceiverStatus();
                mContext.getSharedPreferences(PREF_COMMON, 0).edit().putString(PREF_KEY_RECEIVER, mReceiverIp).commit();
            }
        });

        mReceiverTextView = (TextView) findViewById(R.id.receiver_textview);
        final EditText ipEditText = (EditText) findViewById(R.id.ip_edittext);
        final Button selectButton = (Button) findViewById(R.id.select_button);
        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (ipEditText.getText().length() > 0) {
                    mReceiverIp = ipEditText.getText().toString();
                    Log.d(TAG, "Using ip: " + mReceiverIp);
                    updateReceiverStatus();
                    mContext.getSharedPreferences(PREF_COMMON, 0).edit().putString(PREF_KEY_RECEIVER, mReceiverIp).commit();
                }
            }
        });

        mReceiverIp = mContext.getSharedPreferences(PREF_COMMON, 0).getString(PREF_KEY_RECEIVER, "");
        updateReceiverStatus();


    }

    @Override
    public void onResume() {
        super.onResume();

        // start discovery task
        mDiscoveryTask = new DiscoveryTask();
        mDiscoveryTask.execute();
    }

    @Override
    public void onPause() {
        super.onPause();
        mDiscoveryTask.cancel(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        if (mInputSurface != null) {
            menu.findItem(R.id.action_start).setVisible(false);
            menu.findItem(R.id.action_stop).setVisible(true);
        } else {
            menu.findItem(R.id.action_start).setVisible(true);
            menu.findItem(R.id.action_stop).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_start) {
            Log.d(TAG, "==== start ====");
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    createSocket();
                }
            });
            th.start();
            startScreenCapture();
            invalidateOptionsMenu();
            return true;
        } else if (id == R.id.action_stop) {
            Log.d(TAG, "==== stop ====");
            stopScreenCapture();
            invalidateOptionsMenu();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Log.d(TAG, "User cancelled");
                Toast.makeText(mContext, R.string.user_cancelled, Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d(TAG, "Starting screen capture");
            mResultCode = resultCode;
            mResultData = data;
            setUpMediaProjection();
            //setUpVirtualDisplay();
            startRecording();
            invalidateOptionsMenu();
            Log.d(TAG, "End of starting screen capture");
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mResultData != null) {
            outState.putInt(STATE_RESULT_CODE, mResultCode);
            outState.putParcelable(STATE_RESULT_DATA, mResultData);
        }
    }

    private void updateReceiverStatus() {
        if (mReceiverIp.length() > 0) {
            mReceiverTextView.setText(String.format(mContext.getString(R.string.receiver), mReceiverIp));
        } else {
            mReceiverTextView.setText(R.string.no_receiver);
        }
    }

    private void startScreenCapture() {
        if (mMediaProjection != null) {
            //setUpVirtualDisplay();
            startRecording();
        } else if (mResultCode != 0 && mResultData != null) {
            setUpMediaProjection();
            //setUpVirtualDisplay();
            startRecording();
        } else {
            Log.d(TAG, "Requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            startActivityForResult(
                    mMediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION);
        }
    }

    private void createSocket() {
        try {
            InetAddress serverAddr = InetAddress.getByName(mReceiverIp);
            mSocket = new Socket(serverAddr, VIEWER_PORT);
            mSocketOutputStream = mSocket.getOutputStream();
            OutputStreamWriter osw = new OutputStreamWriter(mSocketOutputStream);
            osw.write("POST\r\n");
            osw.flush();
            mSocketOutputStream.write(H264_PREDEFINED_HEADER);
            return;
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mSocket = null;
        mSocketOutputStream = null;
        Toast.makeText(mContext, String.format(mContext.getString(R.string.connect_failed), mReceiverIp), Toast.LENGTH_SHORT).show();
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

    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }

    private void startRecording() {
        Log.d(TAG, "startRecording");
        mDrainHandler = new Handler();
        //DisplayManager dm = (DisplayManager)getSystemService(Context.DISPLAY_SERVICE);
        //Display defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
        //if (defaultDisplay == null) {
        //    throw new RuntimeException("No display found.");
        //}
        prepareVideoEncoder();

        //try {
        //    mMuxer = new MediaMuxer("/sdcard/video.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        //} catch (IOException ioe) {
        //    throw new RuntimeException("MediaMuxer creation failed", ioe);
        //}

        // Get the display size and density.
        //DisplayMetrics metrics = getResources().getDisplayMetrics();
        //int screenWidth = metrics.widthPixels;
        //int screenHeight = metrics.heightPixels;
        //int screenDensity = metrics.densityDpi;
        //int screenWidth = 1280;
        //int screenHeight = 720;
        //int screenDensity = 320;

        // Start the video input.
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("Recording Display", VIRTUAL_DISPLAY_WIDTH,
                VIRTUAL_DISPLAY_HEIGHT, VIRTUAL_DISPLAY_DENSITY, 0 /* flags */, mInputSurface,
                null /* callback */, null /* handler */);

        // Start the encoders
        drainEncoder();
    }

    private void prepareVideoEncoder() {
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
        int frameRate = VIDEO_FPS;

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        //format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6Mbps
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4096000); // 4Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
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
            //Log.d(TAG, "drainEncoder, bufferIndex: " + bufferIndex);

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

        //Log.d(TAG, "xxxx post delay 10");
        mDrainHandler.postDelayed(mDrainEncoderRunnable, 10);
        return false;
    }

    private void stopScreenCapture() {
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
        mDrainHandler = null;
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
        mDrainEncoderRunnable = null;
        //mTrackIndex = -1;
    }

    private class DiscoveryTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            try {
                DatagramSocket discoverUdpSocket = new DatagramSocket();
                Log.d(TAG, "Bind local port: " + discoverUdpSocket.getLocalPort());
                discoverUdpSocket.setSoTimeout(3000);
                byte[] buf = new byte[1024];
                while (true) {
                    if (!Utils.sendBroadcastMessage(mContext, discoverUdpSocket, DISCOVER_PORT, DISCOVER_MESSAGE)) {
                        Log.w(TAG, "Failed to send discovery message");
                    }
                    Arrays.fill(buf, (byte)0);
                    DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
                    try {
                        discoverUdpSocket.receive(receivePacket);
                    } catch (SocketTimeoutException e) {
                    }
                    Log.d(TAG, "Receive discover response, length: " + receivePacket.getLength());
                    if (receivePacket.getLength() > 9) {
                        String respMsg = new String(receivePacket.getData());
                        Log.d(TAG, "Discover response message: " + respMsg);
                        try {
                            JSONObject json = new JSONObject(respMsg);
                            String name = json.getString("name");
                            String ip = json.getString("id");
                            String width = json.getString("width");
                            String height = json.getString("height");
                            mDiscoverdMap.put(name, ip);
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mDiscoverAdapter.clear();
                                    mDiscoverAdapter.addAll(mDiscoverdMap.keySet());
                                }
                            });
                            Log.d(TAG, "Got receiver name: " + name + ", ip: " + ip + ", width: " + width + ", height: " + height);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                    Thread.sleep(3000);
                }
            } catch (SocketException e) {
                Log.d(TAG, "Failed to create socket for discovery");
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
