/*
 * KalmanLocationManager
 *
 * Copyright (c) 2014 Renato Villone
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.villoren.android.kalmanlocationmanager.lib;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides a means of requesting location updates.
 * <p>
 * Similar to Android's {@link android.location.LocationManager LocationManager}.
 */
public class KalmanLocationManager {

    /**
     * Specifies which of the native location providers to use, or a combination of them.
     */
    public enum UseProvider { GPS, NET, GPS_AND_NET }

    /**
     * Provider string assigned to predicted Location objects.
     */
    public static final String KALMAN_PROVIDER = "kalman";

    /**
     * Logger tag.
     */
    private static final String TAG = KalmanLocationManager.class.getSimpleName();

    /**
     * The Context the KalmanLocationManager is running in.
     */
    private final Context mContext;

    /**
     * Map that associates provided LocationListeners with created LooperThreads.
     */
    private final Map<LocationListener, LooperThread> mListener2Thread;

    /**
     * Constructor.
     *
     * @param context The Context for this KalmanLocationManager.
     */
    public KalmanLocationManager(Context context) {

        mContext = context;
        mListener2Thread = new HashMap<LocationListener, LooperThread>();
    }

    /**
     * Register for {@link android.location.Location Location} estimates using the given LocationListener callback.
     *
     *
     * @param useProvider Specifies which of the native location providers to use, or a combination of them.
     *
     * @param minTimeFilter Minimum time interval between location estimates, in milliseconds.
     *                      Indicates the frequency of predictions to be calculated by the filter,
     *                      thus the frequency of callbacks to be received by the given location listener.
     *
     * @param minTimeGpsProvider Minimum time interval between GPS readings, in milliseconds.
     *                           If {@link UseProvider#NET UseProvider.NET} was set, this value is ignored.
     *
     * @param minTimeNetProvider Minimum time interval between Network readings, in milliseconds.
     *                           If {@link UseProvider#GPS UseProvider.GPS} was set, this value is ignored.
     *
     * @param listener A {@link android.location.LocationListener LocationListener} whose
     *                 {@link android.location.LocationListener#onLocationChanged(android.location.Location) onLocationChanged(Location)}
     *                 method will be called for each location estimate produced by the filter. It will also receive
     *                 the status updates from the native providers.
     *
     * @param forwardProviderReadings Also forward location readings from the native providers to the given listener.
     *                                Note that <i>status</i> updates will always be forwarded.
     *
     */
    public void requestLocationUpdates(
            UseProvider useProvider,
            long minTimeFilter,
            long minTimeGpsProvider,
            long minTimeNetProvider,
            LocationListener listener,
            boolean forwardProviderReadings)
    {
        // Validate arguments
        if (useProvider == null)
            throw new IllegalArgumentException("useProvider can't be null");

        if (listener == null)
            throw new IllegalArgumentException("listener can't be null");

        if (minTimeFilter < 0) {

            Log.w(TAG, "minTimeFilter < 0. Setting to 0");
            minTimeFilter = 0;
        }

        if (minTimeGpsProvider < 0) {

            Log.w(TAG, "minTimeGpsProvider < 0. Setting to 0");
            minTimeGpsProvider = 0;
        }

        if (minTimeNetProvider < 0) {

            Log.w(TAG, "minTimeNetProvider < 0. Setting to 0");
            minTimeNetProvider = 0;
        }

        // Remove this listener if it is already in use
        if (mListener2Thread.containsKey(listener)) {

            Log.d(TAG, "Requested location updates with a listener that is already in use. Removing.");
            removeUpdates(listener);
        }

        LooperThread looperThread = new LooperThread(
                mContext, useProvider, minTimeFilter, minTimeGpsProvider, minTimeNetProvider,
                listener, forwardProviderReadings);

        mListener2Thread.put(listener, looperThread);
    }

    /**
     * Removes location estimates for the specified LocationListener.
     * <p>
     * Following this call, updates will no longer occur for this listener.
     *
     * @param listener Listener object that no longer needs location estimates.
     */
    public void removeUpdates(LocationListener listener) {

        LooperThread looperThread = mListener2Thread.remove(listener);

        if (looperThread == null) {

            Log.d(TAG, "Did not remove updates for given LocationListener. Wasn't registered in this instance.");
            return;
        }

        looperThread.close();
    }


    class LooperThread extends Thread {

        // Static constant
        private static final int THREAD_PRIORITY = 5;

        private static final double DEG_TO_METER = 111225.0;
        private static final double METER_TO_DEG = 1.0 / DEG_TO_METER;

        private static final double TIME_STEP = 1.0;
        private static final double COORDINATE_NOISE = 4.0 * METER_TO_DEG;
        private static final double ALTITUDE_NOISE = 10.0;

        // Context
        private final Context mContext;
        private final Handler mClientHandler;
        private final LocationManager mLocationManager;

        // Settings
        private final UseProvider mUseProvider;
        private final long mMinTimeFilter;
        private final long mMinTimeGpsProvider;
        private final long mMinTimeNetProvider;
        private final LocationListener mClientLocationListener;
        private final boolean mForwardProviderUpdates;

        // Thread
        private Looper mLooper;
        private Handler mOwnHandler;
        private Location mLastLocation;
        private boolean mPredicted;

        /**
         * Three 1-dimension trackers, since the dimensions are independent and can avoid using matrices.
         */
        private Tracker1D mLatitudeTracker, mLongitudeTracker, mAltitudeTracker;

        /**
         *
         * @param context
         * @param useProvider
         * @param minTimeFilter
         * @param minTimeGpsProvider
         * @param minTimeNetProvider
         * @param locationListener
         * @param forwardProviderUpdates
         */
        LooperThread(
                Context context,
                UseProvider useProvider,
                long minTimeFilter,
                long minTimeGpsProvider,
                long minTimeNetProvider,
                LocationListener locationListener,
                boolean forwardProviderUpdates)
        {
            mContext = context;
            mClientHandler = new Handler();
            mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);

            mUseProvider = useProvider;

            mMinTimeFilter = minTimeFilter;
            mMinTimeGpsProvider = minTimeGpsProvider;
            mMinTimeNetProvider = minTimeNetProvider;

            mClientLocationListener = locationListener;
            mForwardProviderUpdates = forwardProviderUpdates;

            start();
        }

        @Override
        public void run() {

            setPriority(THREAD_PRIORITY);

            Looper.prepare();
            mLooper = Looper.myLooper();

            if (mUseProvider == UseProvider.GPS || mUseProvider == UseProvider.GPS_AND_NET) {

                mLocationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, mMinTimeGpsProvider, 0.0f, mOwnLocationListener, mLooper);
            }

            if (mUseProvider == UseProvider.NET || mUseProvider == UseProvider.GPS_AND_NET) {

                mLocationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, mMinTimeNetProvider, 0.0f, mOwnLocationListener, mLooper);
            }

            Looper.loop();
        }

        public void close() {

            mLocationManager.removeUpdates(mOwnLocationListener);
            mLooper.quit();
        }

        private LocationListener mOwnLocationListener = new LocationListener() {

            @Override
            public void onLocationChanged(final Location location) {

                // Reusable
                final double accuracy = location.getAccuracy();
                double position, noise;

                // Latitude
                position = location.getLatitude();
                noise = accuracy * METER_TO_DEG;

                if (mLatitudeTracker == null) {

                    mLatitudeTracker = new Tracker1D(TIME_STEP, COORDINATE_NOISE);
                    mLatitudeTracker.setState(position, 0.0, noise);
                }

                if (!mPredicted)
                    mLatitudeTracker.predict(0.0);

                mLatitudeTracker.update(position, noise);

                // Longitude
                position = location.getLongitude();
                noise = accuracy * Math.cos(Math.toRadians(location.getLatitude())) * METER_TO_DEG ;

                if (mLongitudeTracker == null) {

                    mLongitudeTracker = new Tracker1D(TIME_STEP, COORDINATE_NOISE);
                    mLongitudeTracker.setState(position, 0.0, noise);
                }

                if (!mPredicted)
                    mLongitudeTracker.predict(0.0);

                mLongitudeTracker.update(position, noise);

                // Altitude
                if (location.hasAltitude()) {

                    position = location.getAltitude();
                    noise = accuracy;

                    if (mAltitudeTracker == null) {

                        mAltitudeTracker = new Tracker1D(TIME_STEP, ALTITUDE_NOISE);
                        mAltitudeTracker.setState(position, 0.0, noise);
                    }

                    if (!mPredicted)
                        mAltitudeTracker.predict(0.0);

                    mAltitudeTracker.update(position, noise);
                }

                // Reset predicted flag
                mPredicted = false;

                // Forward update if requested
                if (mForwardProviderUpdates) {

                    mClientHandler.post(new Runnable() {

                        @Override
                        public void run() {

                            mClientLocationListener.onLocationChanged(new Location(location));
                        }
                    });
                }

                // Update last location
                if (location.getProvider().equals(LocationManager.GPS_PROVIDER)
                        || mLastLocation == null || mLastLocation.getProvider().equals(LocationManager.NETWORK_PROVIDER)) {

                    mLastLocation = new Location(location);
                }

                // Enable filter timer if this is our first measurement
                if (mOwnHandler == null) {

                    mOwnHandler = new Handler(mLooper, mOwnHandlerCallback);
                    mOwnHandler.sendEmptyMessageDelayed(0, mMinTimeFilter);
                }
            }

            @Override
            public void onStatusChanged(String provider, final int status, final Bundle extras) {

                final String finalProvider = provider;

                mClientHandler.post(new Runnable() {

                    @Override
                    public void run() {

                        mClientLocationListener.onStatusChanged(finalProvider, status, extras);
                    }
                });
            }

            @Override
            public void onProviderEnabled(String provider) {

                final String finalProvider = provider;

                mClientHandler.post(new Runnable() {

                    @Override
                    public void run() {

                        mClientLocationListener.onProviderEnabled(finalProvider);
                    }
                });
            }

            @Override
            public void onProviderDisabled(String provider) {

                final String finalProvider = provider;

                mClientHandler.post(new Runnable() {

                    @Override
                    public void run() {

                        mClientLocationListener.onProviderDisabled(finalProvider);
                    }
                });
            }
        };



        private Handler.Callback mOwnHandlerCallback = new Handler.Callback() {

            @Override
            public boolean handleMessage(Message msg) {

                // Prepare location
                final Location location = new Location(KALMAN_PROVIDER);

                // Latitude
                mLatitudeTracker.predict(0.0);
                location.setLatitude(mLatitudeTracker.getPosition());

                // Longitude
                mLongitudeTracker.predict(0.0);
                location.setLongitude(mLongitudeTracker.getPosition());

                // Altitude
                if (mLastLocation.hasAltitude()) {

                    mAltitudeTracker.predict(0.0);
                    location.setAltitude(mAltitudeTracker.getPosition());
                }

                // Speed
                if (mLastLocation.hasSpeed())
                    location.setSpeed(mLastLocation.getSpeed());

                // Bearing
                if (mLastLocation.hasBearing())
                    location.setBearing(mLastLocation.getBearing());

                // Accuracy (always has)
                location.setAccuracy((float) (mLatitudeTracker.getAccuracy() * DEG_TO_METER));

                // Set times
                location.setTime(System.currentTimeMillis());

                if (Build.VERSION.SDK_INT >= 17)
                    location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

                // Post the update in the client (UI) thread
                mClientHandler.post(new Runnable() {

                    @Override
                    public void run() {

                        mClientLocationListener.onLocationChanged(location);
                    }
                });

                // Enqueue next prediction
                mOwnHandler.removeMessages(0);
                mOwnHandler.sendEmptyMessageDelayed(0, mMinTimeFilter);
                mPredicted = true;

                return true;
            }
        };
    }

    class Tracker1D {

        // Settings

        /**
         * Time step
         */
        private final double mt, mt2, mt2d2, mt3d2, mt4d4;

        /**
         * Process noise covariance
         */
        private final double mQa, mQb, mQc, mQd;

        /**
         * Estimated state
         */
        private double mXa, mXb;

        /**
         * Estimated covariance
         */
        private double mPa, mPb, mPc, mPd;

        /**
         * Creates a tracker.
         *
         * @param timeStep Delta time between predictions. Usefull to calculate speed.
         * @param processNoise Standard deviation to calculate noise covariance from.
         */
        public Tracker1D(double timeStep, double processNoise) {

            // Lookup time step
            mt = timeStep;
            mt2 = mt * mt;
            mt2d2 = mt2 / 2.0;
            mt3d2 = mt2 * mt / 2.0;
            mt4d4 = mt2 * mt2 / 4.0;

            // Process noise covariance
            double n2 = processNoise * processNoise;
            mQa = n2 * mt4d4;
            mQb = n2 * mt3d2;
            mQc = mQb;
            mQd = n2 * mt2;

            // Estimated covariance
            mPa = mQa;
            mPb = mQb;
            mPc = mQc;
            mPd = mQd;
        }

        /**
         * Reset the filter to the given state.
         * <p>
         * Should be called after creation, unless position and velocity are assumed to be both zero.
         *
         * @param position
         * @param velocity
         * @param noise
         */
        public void setState(double position, double velocity, double noise) {

            // State vector
            mXa = position;
            mXb = velocity;

            // Covariance
            double n2 = noise * noise;
            mPa = n2 * mt4d4;
            mPb = n2 * mt3d2;
            mPc = mPb;
            mPd = n2 * mt2;
        }

        /**
         * Update (correct) with the given measurement.
         *
         * @param position
         * @param noise
         */
        public void update(double position, double noise) {

            double r = noise * noise;

            //  y   =  z   -   H  . x
            double y = position - mXa;

            // S = H.P.H' + R
            double s = mPa + r;
            double si = 1.0 / s;

            // K = P.H'.S^(-1)
            double Ka = mPa * si;
            double Kb = mPc * si;

            // x = x + K.y
            mXa = mXa + Ka * y;
            mXb = mXb + Kb * y;

            // P = P - K.(H.P)
            double Pa = mPa - Ka * mPa;
            double Pb = mPb - Ka * mPb;
            double Pc = mPc - Kb * mPa;
            double Pd = mPd - Kb * mPb;

            mPa = Pa;
            mPb = Pb;
            mPc = Pc;
            mPd = Pd;
        }

        /**
         * Predict state.
         *
         * @param acceleration Should be 0 unless there's some sort of control input (a gas pedal, for instance).
         */
        public void predict(double acceleration) {

            // x = F.x + G.u
            mXa = mXa + mXb * mt + acceleration * mt2d2;
            mXb = mXb + acceleration * mt;

            // P = F.P.F' + Q
            double Pdt = mPd * mt;
            double FPFtb = mPb + Pdt;
            double FPFta = mPa + mt * (mPc + FPFtb);
            double FPFtc = mPc + Pdt;
            double FPFtd = mPd;

            mPa = FPFta + mQa;
            mPb = FPFtb + mQb;
            mPc = FPFtc + mQc;
            mPd = FPFtd + mQd;
        }

        /**
         * @return Estimated position.
         */
        public double getPosition() { return mXa; }

        /**
         * @return Estimated velocity.
         */
        public double getVelocity() { return mXb; }

        /**
         * @return Accuracy
         */
        public double getAccuracy() { return Math.sqrt(mPd / mt2); }
    }
}
