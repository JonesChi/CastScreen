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

import android.media.MediaFormat;

/**
 * Created by yschi on 2015/5/28.
 */
public class Common {
    public static final int VIEWER_PORT = 53515;

    public static final int DISCOVER_PORT = 53515;
    public static final String DISCOVER_MESSAGE = "hello";

    public static final int DEFAULT_SCREEN_WIDTH = 1280;
    public static final int DEFAULT_SCREEN_HEIGHT = 720;
    public static final int DEFAULT_SCREEN_DPI = 320;
    public static final int DEFAULT_VIDEO_BITRATE = 6144000;
    public static final int DEFAULT_VIDEO_FPS = 25;
    public static final String DEFAULT_VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    // Activity to service
    public static final int MSG_REGISTER_CLIENT = 200;
    public static final int MSG_UNREGISTER_CLIENT = 201;
    public static final int MSG_STOP_CAST = 301;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_RECEIVER_IP = "receiver_ip";

    public static final String EXTRA_SCREEN_WIDTH = "screen_width";
    public static final String EXTRA_SCREEN_HEIGHT = "screen_height";
    public static final String EXTRA_SCREEN_DPI = "screen_dpi";
    public static final String EXTRA_VIDEO_FORMAT = "video_format";
    public static final String EXTRA_VIDEO_BITRATE = "video_bitrate";

    public static final String ACTION_STOP_CAST = "com.yschi.castscreen.ACTION_STOP_CAST";
}
