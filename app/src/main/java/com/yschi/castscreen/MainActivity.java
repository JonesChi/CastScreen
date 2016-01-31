/*
 * Copyright (C) 2016 Jones Chi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yschi.castscreen;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaFormat;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;


public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private static final String PREF_COMMON = "common";
    private static final String PREF_KEY_INPUT_RECEIVER = "input_receiver";
    private static final String PREF_KEY_FORMAT = "format";
    private static final String PREF_KEY_RECEIVER = "receiver";
    private static final String PREF_KEY_RESOLUTION = "resolution";
    private static final String PREF_KEY_BITRATE = "bitrate";

    private static final String[] FORMAT_OPTIONS = {
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_VP8
    };

    private static final int[][] RESOLUTION_OPTIONS = {
            {1280, 720, 320},
            {800, 480, 160}
    };

    private static final int[] BITRATE_OPTIONS = {
            6144000, // 6 Mbps
            4096000, // 4 Mbps
            2048000, // 2 Mbps
            1024000 // 1 Mbps
    };

    private static final int REQUEST_MEDIA_PROJECTION = 100;
    private static final String STATE_RESULT_CODE = "result_code";
    private static final String STATE_RESULT_DATA = "result_data";

    private Context mContext;
    private MediaProjectionManager mMediaProjectionManager;
    private Handler mHandler = new Handler(new HandlerCallback());
    private Messenger mMessenger = new Messenger(mHandler);
    private Messenger mServiceMessenger = null;
    private TextView mReceiverTextView;
    private ListView mDiscoverListView;
    private ArrayAdapter<String> mDiscoverAdapter;
    private HashMap<String, String> mDiscoverdMap;
    private String mSelectedFormat = FORMAT_OPTIONS[0];
    private int mSelectedWidth = RESOLUTION_OPTIONS[0][0];
    private int mSelectedHeight = RESOLUTION_OPTIONS[0][1];
    private int mSelectedDpi = RESOLUTION_OPTIONS[0][2];
    private int mSelectedBitrate = BITRATE_OPTIONS[0];
    private String mReceiverIp = "";
    private DiscoveryTask mDiscoveryTask;
    private int mResultCode;
    private Intent mResultData;

    private class HandlerCallback implements Handler.Callback {
        public boolean handleMessage(Message msg) {
            Log.d(TAG, "Handler got event, what: " + msg.what);
            return false;
        }
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Service connected, name: " + name);
            mServiceMessenger = new Messenger(service);
            try {
                Message msg = Message.obtain(null, Common.MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mServiceMessenger.send(msg);
                Log.d(TAG, "Connected to service, send register client back");
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to send message back to service, e: " + e.toString());
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Service disconnected, name: " + name);
            mServiceMessenger = null;
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

        // add server mode option
        mDiscoverAdapter.add(mContext.getString(R.string.server_mode));
        mDiscoverdMap.put(mContext.getString(R.string.server_mode), "");

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
                    mContext.getSharedPreferences(PREF_COMMON, 0).edit().putString(PREF_KEY_INPUT_RECEIVER, mReceiverIp).commit();
                    mContext.getSharedPreferences(PREF_COMMON, 0).edit().putString(PREF_KEY_RECEIVER, mReceiverIp).commit();
                }
            }
        });
        ipEditText.setText(mContext.getSharedPreferences(PREF_COMMON, 0).getString(PREF_KEY_INPUT_RECEIVER, ""));

        Spinner formatSpinner = (Spinner) findViewById(R.id.format_spinner);
        ArrayAdapter<CharSequence> formatAdapter = ArrayAdapter.createFromResource(this,
                R.array.format_options, android.R.layout.simple_spinner_item);
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(formatAdapter);
        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                mSelectedFormat = FORMAT_OPTIONS[i];
                mContext.getSharedPreferences(PREF_COMMON, 0).edit().putInt(PREF_KEY_FORMAT, i).commit();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                mSelectedFormat = FORMAT_OPTIONS[0];
                mContext.getSharedPreferences(PREF_COMMON, 0).edit().putInt(PREF_KEY_FORMAT, 0).commit();
            }
        });
        formatSpinner.setSelection(mContext.getSharedPreferences(PREF_COMMON, 0).getInt(PREF_KEY_FORMAT, 0));

        Spinner resolutionSpinner = (Spinner) findViewById(R.id.resolution_spinner);
        ArrayAdapter<CharSequence> resolutionAdapter = ArrayAdapter.createFromResource(this,
                R.array.resolution_options, android.R.layout.simple_spinner_item);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(resolutionAdapter);
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                mSelectedWidth = RESOLUTION_OPTIONS[i][0];
                mSelectedHeight = RESOLUTION_OPTIONS[i][1];
                mSelectedDpi = RESOLUTION_OPTIONS[i][2];
                mContext.getSharedPreferences(PREF_COMMON, 0).edit().putInt(PREF_KEY_RESOLUTION, i).commit();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                mSelectedWidth = RESOLUTION_OPTIONS[0][0];
                mSelectedHeight = RESOLUTION_OPTIONS[0][1];
                mSelectedDpi = RESOLUTION_OPTIONS[0][2];
                mContext.getSharedPreferences(PREF_COMMON, 0).edit().putInt(PREF_KEY_RESOLUTION, 0).commit();
            }
        });
        resolutionSpinner.setSelection(mContext.getSharedPreferences(PREF_COMMON, 0).getInt(PREF_KEY_RESOLUTION, 0));

        Spinner bitrateSpinner = (Spinner) findViewById(R.id.bitrate_spinner);
        ArrayAdapter<CharSequence> bitrateAdapter = ArrayAdapter.createFromResource(this,
                R.array.bitrate_options, android.R.layout.simple_spinner_item);
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bitrateSpinner.setAdapter(bitrateAdapter);
        bitrateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                mSelectedBitrate = BITRATE_OPTIONS[i];
                mContext.getSharedPreferences(PREF_COMMON, 0).edit().putInt(PREF_KEY_BITRATE, i).commit();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                mSelectedBitrate = BITRATE_OPTIONS[0];
                mContext.getSharedPreferences(PREF_COMMON, 0).edit().putInt(PREF_KEY_BITRATE, 0).commit();
            }
        });
        bitrateSpinner.setSelection(mContext.getSharedPreferences(PREF_COMMON, 0).getInt(PREF_KEY_BITRATE, 0));

        mReceiverIp = mContext.getSharedPreferences(PREF_COMMON, 0).getString(PREF_KEY_RECEIVER, "");
        updateReceiverStatus();
        startService();
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
    protected void onDestroy() {
        super.onDestroy();
        doUnbindService();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        //if (mInputSurface != null) {
        //    menu.findItem(R.id.action_start).setVisible(false);
        //    menu.findItem(R.id.action_stop).setVisible(true);
        //} else {
        //    menu.findItem(R.id.action_start).setVisible(true);
        //    menu.findItem(R.id.action_stop).setVisible(false);
        //}
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
            if (mReceiverIp != null) {
                startCaptureScreen();
                //invalidateOptionsMenu();
            } else {
                Toast.makeText(mContext, R.string.no_receiver, Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_stop) {
            Log.d(TAG, "==== stop ====");
            stopScreenCapture();
            //invalidateOptionsMenu();
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
            startCaptureScreen();
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

    private void startCaptureScreen() {
        if (mResultCode != 0 && mResultData != null) {
            startService();
        } else {
            Log.d(TAG, "Requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            startActivityForResult(
                    mMediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION);
        }
    }

    private void stopScreenCapture() {
        if (mServiceMessenger == null) {
            return;
        }
        final Intent stopCastIntent = new Intent(Common.ACTION_STOP_CAST);
        sendBroadcast(stopCastIntent);
        /*
        try {
            Message msg = Message.obtain(null, Common.MSG_STOP_CAST);
            mServiceMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send stop message to service");
            e.printStackTrace();
        }*/
    }

    private void startService() {
        if (mResultCode != 0 && mResultData != null && mReceiverIp != null) {
            Intent intent = new Intent(this, CastService.class);
            intent.putExtra(Common.EXTRA_RESULT_CODE, mResultCode);
            intent.putExtra(Common.EXTRA_RESULT_DATA, mResultData);
            intent.putExtra(Common.EXTRA_RECEIVER_IP, mReceiverIp);
            intent.putExtra(Common.EXTRA_VIDEO_FORMAT, mSelectedFormat);
            intent.putExtra(Common.EXTRA_SCREEN_WIDTH, mSelectedWidth);
            intent.putExtra(Common.EXTRA_SCREEN_HEIGHT, mSelectedHeight);
            intent.putExtra(Common.EXTRA_SCREEN_DPI, mSelectedDpi);
            intent.putExtra(Common.EXTRA_VIDEO_BITRATE, mSelectedBitrate);
            Log.d(TAG, "===== start service =====");
            startService(intent);
            bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } else {
            Intent intent = new Intent(this, CastService.class);
            startService(intent);
            bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void doUnbindService() {
        if (mServiceMessenger != null) {
            try {
                Message msg = Message.obtain(null, Common.MSG_UNREGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to send unregister message to service, e: " + e.toString());
                e.printStackTrace();
            }
            unbindService(mServiceConnection);
        }
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
                    if (!Utils.sendBroadcastMessage(mContext, discoverUdpSocket, Common.DISCOVER_PORT, Common.DISCOVER_MESSAGE)) {
                        Log.w(TAG, "Failed to send discovery message");
                    }
                    Arrays.fill(buf, (byte)0);
                    DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
                    try {
                        discoverUdpSocket.receive(receivePacket);
                        String ip = receivePacket.getAddress().getHostAddress();
                        Log.d(TAG, "Receive discover response from " + ip + ", length: " + receivePacket.getLength());
                        if (receivePacket.getLength() > 9) {
                            String respMsg = new String(receivePacket.getData());
                            Log.d(TAG, "Discover response message: " + respMsg);
                            try {
                                JSONObject json = new JSONObject(respMsg);
                                String name = json.getString("name");
                                //String id = json.getString("id");
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
                    } catch (SocketTimeoutException e) {
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
