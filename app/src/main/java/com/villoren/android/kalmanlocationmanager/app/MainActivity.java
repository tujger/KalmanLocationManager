/*
 * MainActivity
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

package com.villoren.android.kalmanlocationmanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.villoren.android.kalmanlocationmanager.lib.KalmanLocationManager;

import java.util.Iterator;

import static com.villoren.android.kalmanlocationmanager.lib.KalmanLocationManager.UseProvider;

@SuppressWarnings("MissingPermission")
public class MainActivity extends Activity implements GpsStatus.Listener {

    // Constant

    /**
     * Request location updates with the highest possible frequency on gps.
     * Typically, this means one update per second for gps.
     */
    private static final long GPS_TIME = 2000;

    /**
     * For the network provider, which gives locations with less accuracy (less reliable),
     * request updates every 5 seconds.
     */
    private static final long NET_TIME = 600000;

    /**
     * For the filter-time argument we use a "real" value: the predictions are triggered by a timer.
     * Lets say we want 5 updates (estimates) per second = update each 200 millis.
     */
    private static final long FILTER_TIME = 1000;

    // Context
    private KalmanLocationManager mKalmanLocationManager;
    private SharedPreferences mPreferences;

    // UI elements
    private MapView mMapView;
    private TextView tvGps;
    private TextView tvNet;
    private TextView tvKal;
    private TextView tvAlt;
    private LinearLayout layoutSatellites;
    private SeekBar sbZoom;
    // Map elements
    private GoogleMap mGoogleMap;

    private Circle mGpsCircle;
    private Circle mNetCircle;
    // Textview animation
    private Animation mGpsAnimation;

    private Animation mNetAnimation;
    private Animation mKalAnimation;
    // GoogleMaps own OnLocationChangedListener (not android's LocationListener)
    private LocationSource.OnLocationChangedListener mOnLocationChangedListener;

    private String info = "-";
    private LocationManager mService;
    private TextView gpsInfo;
    private TextView netInfo;
    private LinearLayout layoutGpsStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Context
        mKalmanLocationManager = new KalmanLocationManager(this);
        mPreferences = getPreferences(Context.MODE_PRIVATE);

        // Init maps
        int result = MapsInitializer.initialize(this);

        if (result != ConnectionResult.SUCCESS) {

            GooglePlayServicesUtil.getErrorDialog(result, this, 0).show();
            return;
        }

        // UI elements


        mMapView = (MapView) findViewById(R.id.mapView);
        mMapView.onCreate(savedInstanceState);

        tvGps = (TextView) findViewById(R.id.tvGps);
        tvNet = (TextView) findViewById(R.id.tvNet);
        tvKal = (TextView) findViewById(R.id.tvKal);
        tvAlt = (TextView) findViewById(R.id.tvAlt);
        gpsInfo = (TextView) findViewById(R.id.gpsInfo);
        netInfo = (TextView) findViewById(R.id.netInfo);
        sbZoom = (SeekBar) findViewById(R.id.sbZoom);
        layoutSatellites = (LinearLayout) findViewById(R.id.layout_satellites);
        layoutGpsStatus = (LinearLayout) findViewById(R.id.layout_gps_status);
        layoutGpsStatus.findViewById(R.id.info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int v = layoutGpsStatus.findViewById(R.id.layout_gps_info).getVisibility();
                if(v == View.VISIBLE) {
                    layoutGpsStatus.findViewById(R.id.layout_gps_info).setVisibility(View.GONE);
                } else {
                    layoutGpsStatus.findViewById(R.id.layout_gps_info).setVisibility(View.VISIBLE);
                }
            }
        });

        // Initial zoom level
        sbZoom.setProgress(mPreferences.getInt("zoom", 80));

        // Map settings
        mGoogleMap = mMapView.getMap();
        UiSettings uiSettings = mGoogleMap.getUiSettings();
        uiSettings.setAllGesturesEnabled(true);
        uiSettings.setCompassEnabled(false);
        uiSettings.setZoomControlsEnabled(false);
        uiSettings.setMyLocationButtonEnabled(false);
//        mGoogleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mGoogleMap.setLocationSource(mLocationSource);
        mGoogleMap.setMyLocationEnabled(true);

        // Map elements
        CircleOptions gpsCircleOptions = new CircleOptions()
                .center(new LatLng(0.0, 0.0))
                .radius(1.0)
                .fillColor(getResources().getColor(R.color.activity_main_fill_gps))
                .strokeColor(getResources().getColor(R.color.activity_main_stroke_gps))
                .strokeWidth(1.0f)
                .visible(false);

        mGpsCircle = mGoogleMap.addCircle(gpsCircleOptions);

        CircleOptions netCircleOptions = new CircleOptions()
                .center(new LatLng(0.0, 0.0))
                .radius(1.0)
                .fillColor(getResources().getColor(R.color.activity_main_fill_net))
                .strokeColor(getResources().getColor(R.color.activity_main_stroke_net))
                .strokeWidth(1.0f)
                .visible(false);

        mNetCircle = mGoogleMap.addCircle(netCircleOptions);

        // TextView animation
        final float fromAlpha = 1.0f, toAlpha = 0.5f;

        mGpsAnimation = new AlphaAnimation(fromAlpha, toAlpha);
        mGpsAnimation.setDuration(GPS_TIME / 2);
        mGpsAnimation.setFillAfter(true);
        tvGps.startAnimation(mGpsAnimation);

        mNetAnimation = new AlphaAnimation(fromAlpha, toAlpha);
        mNetAnimation.setDuration(NET_TIME / 2);
        mNetAnimation.setFillAfter(true);
        tvNet.startAnimation(mNetAnimation);

        mKalAnimation = new AlphaAnimation(fromAlpha, toAlpha);
        mKalAnimation.setDuration(FILTER_TIME / 2);
        mKalAnimation.setFillAfter(true);
        tvKal.startAnimation(mKalAnimation);

        // Init altitude textview
//        tvAlt.setText(getString(R.string.activity_main_fmt_alt, "-"));
        tvAlt.setText(info);

        mService = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        mService.addGpsStatusListener(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {

            case R.id.action_settings:

                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return true;

            default:

                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();

        // Request location updates with the highest possible frequency on gps.
        // Typically, this means one update per second for gps.

        // For the network provider, which gives locations with less accuracy (less reliable),
        // request updates every 5 seconds.

        // For the filtertime argument we use a "real" value: the predictions are triggered by a timer.
        // Lets say we want 5 updates per second = update each 200 millis.

        mKalmanLocationManager.requestLocationUpdates(
                UseProvider.GPS_AND_NET, FILTER_TIME, GPS_TIME, NET_TIME, mLocationListener, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMapView.onPause();

        // Remove location updates
        mKalmanLocationManager.removeUpdates(mLocationListener);

        // Store zoom level
        mPreferences.edit().putInt("zoom", sbZoom.getProgress()).apply();
    }

    /**
     * Listener used to get updates from KalmanLocationManager (the good old Android LocationListener).
     */
    private LocationListener mLocationListener = new LocationListener() {

        @Override
        public void onLocationChanged(Location location) {

            LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

            // GPS location
            if (location.getProvider().equals(LocationManager.GPS_PROVIDER)) {

                mGpsCircle.setCenter(latLng);
                mGpsCircle.setRadius(location.getAccuracy());
                mGpsCircle.setVisible(true);

                tvGps.clearAnimation();
                tvGps.startAnimation(mGpsAnimation);
            }

            // Network location
            if (location.getProvider().equals(LocationManager.NETWORK_PROVIDER)) {

                mNetCircle.setCenter(latLng);
                mNetCircle.setRadius(location.getAccuracy());
                mNetCircle.setVisible(true);

                tvNet.clearAnimation();
                tvNet.startAnimation(mNetAnimation);
            }

            // If Kalman location and google maps activated the supplied mLocationSource
            if (location.getProvider().equals(KalmanLocationManager.KALMAN_PROVIDER)
                    && mOnLocationChangedListener != null) {

                // Update blue "myLocation" dot
                mOnLocationChangedListener.onLocationChanged(location);

                // Update camera position
                CameraPosition position = CameraPosition.builder(mGoogleMap.getCameraPosition())
                        .target(latLng)
                        .bearing(location.getBearing())
                        .zoom(sbZoom.getProgress() / 10.0f + 10.0f)
                    .build();

                CameraUpdate update = CameraUpdateFactory.newCameraPosition(position);
                mGoogleMap.animateCamera(update, (int) FILTER_TIME, null);

                // Update altitude
                String altitude = location.hasAltitude() ? String.format("%.1f", location.getAltitude()) : "-";
                tvAlt.setText(info + "\n" + location.getProvider()+": "+location.getAccuracy());

//                tvAlt.setText(getString(R.string.activity_main_fmt_alt, altitude));

                // Animate textview
                tvKal.clearAnimation();
                tvKal.startAnimation(mKalAnimation);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

            String statusString = "Unknown";

            switch (status) {

                case LocationProvider.OUT_OF_SERVICE:
                    statusString = "Out of service";
                    break;

                case LocationProvider.TEMPORARILY_UNAVAILABLE:
                    statusString = "Temporary unavailable";
                    break;

                case LocationProvider.AVAILABLE:
                    statusString = "Available";
                    break;
            }
            info = statusString;
            tvAlt.setText(info);
            if(provider.equals(LocationManager.GPS_PROVIDER)){
                gpsInfo.setText(statusString);
            } else if(provider.equals(LocationManager.NETWORK_PROVIDER)){
                netInfo.setText(statusString);
            }

            /*Toast.makeText(
                    MainActivity.this,
                    String.format("Provider '%s' status: %s", provider, statusString),
                    Toast.LENGTH_SHORT)
            .show();*/
        }

        @Override
        public void onProviderEnabled(String provider) {

            Toast.makeText(
                    MainActivity.this, String.format("Provider '%s' enabled", provider), Toast.LENGTH_SHORT).show();

            info = provider + " enabled";
            // Remove strike-thru in label
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                tvGps.setPaintFlags(tvGps.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                tvGps.invalidate();
            }
            if(provider.equals(LocationManager.GPS_PROVIDER)){
                gpsInfo.setText("Enabled");
            } else if(provider.equals(LocationManager.NETWORK_PROVIDER)){
                netInfo.setText("Enabled");
            }

            if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
                tvNet.setPaintFlags(tvNet.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                tvNet.invalidate();
            }
        }

        @Override
        public void onProviderDisabled(String provider) {

            Toast.makeText(
                    MainActivity.this, String.format("Provider '%s' disabled", provider), Toast.LENGTH_SHORT).show();

            info = provider + " disabled";
            // Set strike-thru in label and hide accuracy circle
            if (provider.equals(LocationManager.GPS_PROVIDER)) {

                tvGps.setPaintFlags(tvGps.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvGps.invalidate();
                mGpsCircle.setVisible(false);
            }
            if(provider.equals(LocationManager.GPS_PROVIDER)){
                gpsInfo.setText("Disabled");
            } else if(provider.equals(LocationManager.NETWORK_PROVIDER)){
                netInfo.setText("Disabled");
            }

            if (provider.equals(LocationManager.NETWORK_PROVIDER)) {

                tvNet.setPaintFlags(tvNet.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvNet.invalidate();
                mNetCircle.setVisible(false);
            }
        }
    };

    /**
     * Location Source for google maps 'my location' layer.
     */
    private LocationSource mLocationSource = new LocationSource() {

        @Override
        public void activate(OnLocationChangedListener onLocationChangedListener) {

            mOnLocationChangedListener = onLocationChangedListener;
        }

        @Override
        public void deactivate() {

            mOnLocationChangedListener = null;
        }
    };

    @Override
    public void onGpsStatusChanged(int event) {
        GpsStatus mStatus = mService.getGpsStatus(null);
        switch (event) {
            case GpsStatus.GPS_EVENT_STARTED:
                info = "gps started";
                tvAlt.setText(info);
                // Do Something with mStatus info
                break;

            case GpsStatus.GPS_EVENT_STOPPED:
                info = "gps stopped";
                tvAlt.setText(info);
                // Do Something with mStatus info
                break;

            case GpsStatus.GPS_EVENT_FIRST_FIX:
                info = "gps first fix: " + mStatus.getTimeToFirstFix();
                tvAlt.setText(info);
                // Do Something with mStatus info
                break;

            case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                int total = 0;
                int i = 0;
                float maxSnr = 0;
                layoutSatellites.removeAllViews();
                Iterator<GpsSatellite> iter = mStatus.getSatellites().iterator();

                while(iter.hasNext()){
                    GpsSatellite entry = iter.next();
                    total++;
                    if(entry.getSnr() > maxSnr) maxSnr = entry.getSnr();

                    if(entry.getSnr() < 10) continue;

                    LinearLayout view = (LinearLayout) getLayoutInflater().inflate(R.layout.view_satellite, null);
                    int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 35, getResources().getDisplayMetrics());

                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
                    view.setLayoutParams(params);

                    ProgressBar bar = (ProgressBar) view.findViewById(R.id.bar);

//                    bar.setMax(50);
                    bar.setProgress((int) entry.getSnr());
//                    bar.setProgress(entry.);

                    TextView number = (TextView) view.findViewById(R.id.number);
                    TextView snr = (TextView) view.findViewById(R.id.snr);
                    snr.setText(""+entry.getSnr());
                    number.setText(""+entry.getPrn());//+"\n"+/*entry.getAzimuth()+"\n"+*/entry.getElevation()+"\n"+
//                            (entry.usedInFix() ? "F" : "-") +
//                            (entry.hasAlmanac() ? "A" : "-")+
//                            (entry.hasEphemeris() ? "E" : "-"));
                    layoutSatellites.addView(view);

                    i++;
                    if(entry.usedInFix()) {
                        number.setTextColor(Color.BLUE);
                        snr.setTextColor(Color.BLUE);
                    } else if(entry.getSnr() > 10) {
                        number.setTextColor(Color.BLACK);
                        snr.setTextColor(Color.BLACK);
                    }
                }
                iter = mStatus.getSatellites().iterator();
                while(iter.hasNext()){
                    GpsSatellite entry = iter.next();
                    if(entry.getSnr() >= 10) continue;

                    LinearLayout view = (LinearLayout) getLayoutInflater().inflate(R.layout.view_satellite, null);
                    int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 35, getResources().getDisplayMetrics());
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
                    view.setLayoutParams(params);

                    ProgressBar bar = (ProgressBar) view.findViewById(R.id.bar);

//                    bar.setMax(50);
                    bar.setProgress((int) entry.getSnr());
//                    bar.setProgress(entry.);

                    TextView number = (TextView) view.findViewById(R.id.number);
                    TextView snr = (TextView) view.findViewById(R.id.snr);
                    snr.setText(""+entry.getSnr());
                    number.setText(""+entry.getPrn());//+"\n"+/*entry.getAzimuth()+"\n"+*/entry.getElevation()+"\n"+
//                            (entry.usedInFix() ? "F" : "-") +
//                            (entry.hasAlmanac() ? "A" : "-")+
//                            (entry.hasEphemeris() ? "E" : "-"));
                    layoutSatellites.addView(view);

                    number.setTextColor(Color.GRAY);
                    snr.setTextColor(Color.GRAY);
                }

                ((TextView)layoutGpsStatus.findViewById(R.id.total)).setText(""+total);
                ((TextView)layoutGpsStatus.findViewById(R.id.active)).setText(""+i);
                ((TextView)layoutGpsStatus.findViewById(R.id.max_snr)).setText(""+maxSnr);

//                info = "gps satellite status: " + i;
//                tvAlt.setText(info);

                // Do Something with mStatus info
                break;
        }
    }
}
