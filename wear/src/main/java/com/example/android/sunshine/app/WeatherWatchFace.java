/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {

    private static final String LOG_TAG = WeatherWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static int getIconForWeatherId(int weatherId) {
        if (weatherId == 0) {
            return R.drawable.ic_none;
        } else if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        }
        return -1;
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener
            ,GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_INFO_PATH = "/weather-info";
        private static final String KEY_HIGH = "high";
        private static final String KEY_LOW = "low";
        private static final String KEY_ICON_ID = "icon_id";
        private static final String KEY_UUID = "uuid";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Bitmap mBackgroundBitmap;
        Bitmap mAmbientBackgroundBitmap;
        Paint mHandPaint;
        Paint mAmbientHandPaint;
        Paint mSecondHandPaint;

        Paint mWeatherHighPaint;
        Paint mWeatherLowPaint;
        Bitmap mWeatherIcon;
        String mWeatherHigh;
        String mWeatherLow;
        float mWatchWidth;
        float mWatchHeight;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setStatusBarGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setHotwordIndicatorGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = WeatherWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));
            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg_square);

//            mLogoPaint = new Paint();
//            mLogoPaint.setColor(Color.BLACK);
//            mLogoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_logo);

            Drawable b = getResources().getDrawable(getIconForWeatherId(0));
            mWeatherIcon = ((BitmapDrawable) b).getBitmap();
            mWeatherHigh = "--";
            mWeatherLow = "--";

            mWeatherHighPaint = createTextPaint(Color.WHITE);
            mWeatherHighPaint.setTextSize(20);
            mWeatherLowPaint = createTextPaint(Color.WHITE);
            mWeatherLowPaint.setTextSize(20);

            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mAmbientHandPaint = new Paint();
            mAmbientHandPaint.setColor(resources.getColor(R.color.analog_hands_2));
            mAmbientHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mAmbientHandPaint.setAntiAlias(true);
            mAmbientHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mSecondHandPaint = new Paint();
            mSecondHandPaint.setColor(resources.getColor(R.color.analog_second_hand));
            mSecondHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_second_hand_stroke));
            mSecondHandPaint.setAntiAlias(true);
            mSecondHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new Time();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createBoldTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;

                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(LOG_TAG,"Surface Change");
            super.onSurfaceChanged(holder, format, width, height);
            mWatchWidth = (float)width;
            mWatchHeight = (float)height;

            if (!mBurnInProtection && !mLowBitAmbient) {
                mAmbientBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg_ambient);
                float scale = (mWatchWidth) / (float) mBackgroundBitmap.getWidth();
                mAmbientBackgroundBitmap = Bitmap.createScaledBitmap(mAmbientBackgroundBitmap,
                        (int) (mAmbientBackgroundBitmap.getWidth() * scale),
                        (int) (mAmbientBackgroundBitmap.getHeight() * scale), true);

            } else {
                mAmbientBackgroundBitmap = null;
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            Log.d(LOG_TAG,"Apply Insets");
            super.onApplyWindowInsets(insets);
            boolean isRound = insets.isRound();
            if (isRound) {
                mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg_round);
            } else {
                mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg_square);
            }
            float scale = (mWatchWidth) / (float) mBackgroundBitmap.getWidth();
            mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                    (int) (mBackgroundBitmap.getWidth() * scale),
                    (int) (mBackgroundBitmap.getHeight() * scale), true);

        }

            @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();

            // Draw the background.

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                if (!mBurnInProtection && !mLowBitAmbient && (mAmbientBackgroundBitmap != null)) {
                    canvas.drawBitmap(mAmbientBackgroundBitmap, 0, 0, mBackgroundPaint);
                }
            } else {
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
                canvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);
            }

            if (mWeatherHigh != null && mWeatherLow != null && mWeatherIcon != null) {
                // Draw a line to separate date and time from weather elements

//                float highTextLen = mWeatherHighPaint.measureText(mWeatherHigh);
                float highTextLen = 30;

                if (mAmbient) {
//                    float lowTextLen = mTextTempLowAmbientPaint.measureText(mWeatherLow);
//                    float xOffset = bounds.centerX() - ((highTextLen + lowTextLen + 20) / 2);
//                    canvas.drawText(mWeatherHigh, xOffset, mWeatherYOffset, mTextTempHighPaint);
//                    canvas.drawText(mWeatherLow, xOffset + highTextLen + 20, mWeatherYOffset, mTextTempLowAmbientPaint);
                } else {
                    float labelY = bounds.centerY() + 9;
                    float label1X = bounds.centerX() - 63 - (mWeatherLowPaint.measureText(mWeatherLow) / 2);
                    float label2X = bounds.centerX() + 63 - (mWeatherHighPaint.measureText(mWeatherHigh) / 2);
                    canvas.drawText(mWeatherLow, label1X, labelY, mWeatherLowPaint);
                    canvas.drawText(mWeatherHigh, label2X, labelY, mWeatherHighPaint);
                    canvas.drawBitmap(mWeatherIcon
                            , bounds.centerX() - (mWeatherIcon.getWidth() / 2)
                            , bounds.centerY() - (mWeatherIcon.getHeight() / 2)
                            , null);
                }
            }

            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            float secRot = mTime.second / 30f * (float) Math.PI;
            int minutes = mTime.minute;
            float minRot = minutes / 30f * (float) Math.PI;
            float hrRot = ((mTime.hour + (minutes / 60f)) / 6f) * (float) Math.PI;

            float secLength = centerX - 20;
            float minLength = centerX - 40;
            float hrLength = centerX - 80;
            float gapLenth = 40;

            if (!mAmbient) {
                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                float secGapX = (float) Math.sin(secRot) * gapLenth;
                float secGapY = (float) -Math.cos(secRot) * gapLenth;

                canvas.drawLine(centerX + secGapX, centerY + secGapY, centerX + secX, centerY + secY, mSecondHandPaint);
            }

            float minX = (float) Math.sin(minRot) * minLength;
            float minY = (float) -Math.cos(minRot) * minLength;
            float minGapX = (float) Math.sin(minRot) * gapLenth;
            float minGapY = (float) -Math.cos(minRot) * gapLenth;
            canvas.drawLine(centerX + minGapX, centerY + minGapY, centerX + minX, centerY + minY, (mAmbient?mAmbientHandPaint:mHandPaint));

            float hrX = (float) Math.sin(hrRot) * hrLength;
            float hrY = (float) -Math.cos(hrRot) * hrLength;
            float hrGapX = (float) Math.sin(hrRot) * gapLenth;
            float hrGapY = (float) -Math.cos(hrRot) * gapLenth;
            canvas.drawLine(centerX + hrGapX, centerY + hrGapY, centerX + hrX, centerY + hrY, (mAmbient?mAmbientHandPaint:mHandPaint));
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "Got Data Update");

            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d(LOG_TAG, path);
                    if (path.equals(WEATHER_INFO_PATH)) {
                        if (dataMap.containsKey(KEY_HIGH)) {
                            mWeatherHigh = dataMap.getString(KEY_HIGH);
                            Log.d(LOG_TAG, "High: " + mWeatherHigh);
                        }

                        if (dataMap.containsKey(KEY_LOW)) {
                            mWeatherLow = dataMap.getString(KEY_LOW);
                            Log.d(LOG_TAG, "Low: " + mWeatherLow);
                        }

                        if (dataMap.containsKey(KEY_ICON_ID)) {
                            int weatherId = dataMap.getInt(KEY_ICON_ID);
                            Drawable b = getResources().getDrawable(getIconForWeatherId(weatherId));
                            mWeatherIcon = ((BitmapDrawable) b).getBitmap();
                        }

                        invalidate();
                    }
                }
            }
        }

        public void requestWeatherFromDevice() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString(KEY_UUID, UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(LOG_TAG, "Failed to request weather from device");
                            } else {
                                Log.d(LOG_TAG, "Weather request success");
                            }
                        }
                    });
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            requestWeatherFromDevice();

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }
    }
}
