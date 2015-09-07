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

import android.app.LoaderManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.CursorLoader;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.example.android.sunshine.app.data.WeatherContract;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements android.support.v4.content.Loader.OnLoadCompleteListener<Cursor> {
        private static final int DETAILS_LOADER = 1337;
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mHourTextPaint;
        Paint mMinuteTextPaint;
        Paint mHighTextPaint;
        Paint mLowTextPaint;
        Paint mDatePaint;

        SimpleDateFormat mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");

        boolean mAmbient;

        Time mTime;
        Date mDate;

        float mYOffset;
        private float mDateMargin;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private Rect tempRect;
        private float mLineWidth;
        private CursorLoader mCursorLoader;
        private Uri mUri;
        private int mWeatherId = 501;
        private String mLowString = "--°";
        private String mHighString = "--°";
        private Resources mResources;


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            mResources = MyWatchFace.this.getResources();
            mYOffset = mResources.getDimension(R.dimen.digital_y_offset);
            mDateMargin = mResources.getDimension(R.dimen.digital_date_margin);
            mLineWidth = mResources.getDimension(R.dimen.digital_line_width);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mResources.getColor(R.color.digital_background));


            int textColor = ContextCompat.getColor(MyWatchFace.this, R.color.digital_text);
            mHourTextPaint = createTextPaint(textColor, BOLD_TYPEFACE, 255);
            mMinuteTextPaint = createTextPaint(textColor, NORMAL_TYPEFACE, 255);

            mDatePaint = createTextPaint(textColor, NORMAL_TYPEFACE, 160);

            mHighTextPaint = createTextPaint(textColor, BOLD_TYPEFACE, 255);
            mLowTextPaint = createTextPaint(textColor, NORMAL_TYPEFACE, 255);
            mTime = new Time();
            mDate = new Date(System.currentTimeMillis());

            String locationSetting = Utility.getPreferredLocation(MyWatchFace.this);
            mUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationSetting, System.currentTimeMillis());
            mCursorLoader = new CursorLoader(MyWatchFace.this, mUri, null, null, null, null);
            mCursorLoader.registerListener(DETAILS_LOADER, this);
            mCursorLoader.startLoading();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (mCursorLoader != null) {
                mCursorLoader.unregisterListener(this);
                mCursorLoader.cancelLoad();
                mCursorLoader.stopLoading();
            }
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface, int alpha) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            paint.setAlpha(alpha);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
                mDate.setTime(System.currentTimeMillis());
            } else {
                unregisterReceiver();
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
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float smallTextSize = resources.getDimension(R.dimen.digital_text_size_small);

            mHourTextPaint.setTextSize(textSize);
            mMinuteTextPaint.setTextSize(textSize);
            mHighTextPaint.setTextSize(smallTextSize);
            mLowTextPaint.setTextSize(smallTextSize);

            mDatePaint.setTextSize(smallTextSize);


        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
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
                    mHourTextPaint.setAntiAlias(!inAmbientMode);
                    mMinuteTextPaint.setAntiAlias(!inAmbientMode);
                    mHighTextPaint.setAntiAlias(!inAmbientMode);
                    mLowTextPaint.setAntiAlias(!inAmbientMode);

                    mDatePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String hour = String.format("%02d", mTime.hour);
            String minute = String.format(":%02d", mTime.minute);
            float totalWidth = mHourTextPaint.measureText(hour) + mMinuteTextPaint.measureText(minute);
            float timeXOffset = canvas.getWidth() / 2 - totalWidth / 2;
            canvas.drawText(hour, timeXOffset, mYOffset, mHourTextPaint);
            canvas.drawText(minute, timeXOffset + mHourTextPaint.measureText(hour), mYOffset, mMinuteTextPaint);

            tempRect = new Rect();
            mHourTextPaint.getTextBounds(hour, 0, 1, tempRect);
            mDate.setTime(System.currentTimeMillis());
            String date = mDateFormat.format(mDate);
            float dateXOffset = canvas.getWidth() / 2 - mDatePaint.measureText(date) / 2;
            float dateYOffset = mYOffset + tempRect.height();
            canvas.drawText(date, dateXOffset, dateYOffset, mDatePaint);

            mDatePaint.getTextBounds(date, 0, 1, tempRect);
            float lineYoffset = dateYOffset + tempRect.height() + mDateMargin;
            canvas.drawLine(canvas.getWidth() / 2 - mLineWidth / 2, lineYoffset, canvas.getWidth() / 2 + mLineWidth / 2, lineYoffset, mDatePaint);

            Bitmap weatherIcon = BitmapFactory.decodeResource(mResources, Utility.getIconResourceForWeatherCondition(mWeatherId));
            int tempWidth = (int) (weatherIcon.getWidth() + mDateMargin + mHighTextPaint.measureText(mHighString) + mLowTextPaint.measureText(mHighString));

            canvas.drawBitmap(weatherIcon, canvas.getWidth() / 2 - tempWidth / 2, lineYoffset + mDateMargin, mBackgroundPaint);
            canvas.drawText(mHighString, canvas.getWidth() / 2 - tempWidth / 2 + mDateMargin + weatherIcon.getWidth(), lineYoffset + mDateMargin + weatherIcon.getHeight() / 2, mHighTextPaint);
            canvas.drawText(mLowString, canvas.getWidth() / 2 - tempWidth / 2 + mDateMargin + weatherIcon.getWidth() + mHighTextPaint.measureText(mHighString), lineYoffset + mDateMargin + weatherIcon.getHeight() / 2, mLowTextPaint);
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

        public static final int COL_WEATHER_MAX_TEMP = 3;
        public static final int COL_WEATHER_MIN_TEMP = 4;
        public static final int COL_WEATHER_CONDITION_ID = 9;

        @Override
        public void onLoadComplete(android.support.v4.content.Loader<Cursor> loader, Cursor data) {
            if (null == data || data.getCount() == 0) {
                mWeatherId = 501;
                mLowString = Utility.formatTemperature(MyWatchFace.this, 16.0);
                mHighString = Utility.formatTemperature(MyWatchFace.this, 28.0);
                return;
            }
            data.moveToFirst();
            mWeatherId = data.getInt(COL_WEATHER_CONDITION_ID);

            // Read low temperature from cursor and update view
            double low = data.getDouble(COL_WEATHER_MIN_TEMP);
            mLowString = Utility.formatTemperature(MyWatchFace.this, low);

            double high = data.getDouble(COL_WEATHER_MAX_TEMP);
            mHighString = Utility.formatTemperature(MyWatchFace.this, high);

        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
