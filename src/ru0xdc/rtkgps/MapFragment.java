package ru0xdc.rtkgps;

import static junit.framework.Assert.assertNotNull;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import butterknife.InjectView;
import butterknife.Views;

import org.osmdroid.ResourceProxy;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.bing.BingMapTileSource;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ScaleBarOverlay;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import ru0xdc.rtkgps.view.GTimeView;
import ru0xdc.rtkgps.view.SolutionView;
import ru0xdc.rtkgps.view.StreamIndicatorsView;
import ru0xdc.rtklib.RtkCommon;
import ru0xdc.rtklib.RtkCommon.Position3d;
import ru0xdc.rtklib.RtkControlResult;
import ru0xdc.rtklib.RtkServerStreamStatus;
import ru0xdc.rtklib.Solution;
import ru0xdc.rtklib.constants.SolutionStatus;

import java.util.Timer;
import java.util.TimerTask;

public class MapFragment extends Fragment {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    static final String TAG = MapFragment.class.getSimpleName();

    private static final String SHARED_PREFS_NAME = "map";
    private static final String PREFS_TITLE_SOURCE = "title_source";
    private static final String PREFS_SCROLL_X = "scroll_x";
    private static final String PREFS_SCROLL_Y = "scroll_y";
    private static final String PREFS_ZOOM_LEVEL = "zoom_level";

    private static final String MAP_MODE_BING="BingMap";
    private static final String MAP_MODE_BING_AERIAL="Bing aerial";
    private static final String MAP_MODE_BING_ROAD="Bing road";

    private Timer mStreamStatusUpdateTimer;
    private RtkServerStreamStatus mStreamStatus;
    private ResourceProxy mResourceProxy;

    private BingMapTileSource mBingRoadTileSource, mBingAerialTileSource;
    private SolutionPathOverlay mPathOverlay;
    private MyLocationNewOverlay mMyLocationOverlay;
    private CompassOverlay mCompassOverlay;
    private ScaleBarOverlay mScaleBarOverlay;

    private RtkControlResult mRtkStatus;

    @InjectView(R.id.streamIndicatorsView) StreamIndicatorsView mStreamIndicatorsView;
    @InjectView(R.id.map_container) ViewGroup mMapViewContainer;
    @InjectView(R.id.gtimeView) GTimeView mGTimeView;
    @InjectView(R.id.solutionView) SolutionView mSolutionView;

    private MapView mMapView;


    public MapFragment() {
        mStreamStatus = new RtkServerStreamStatus();
        mRtkStatus = new RtkControlResult();

    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mResourceProxy = new ResourceProxyImpl(activity.getApplicationContext());
        BingMapTileSource.retrieveBingKey(activity);
        mBingRoadTileSource = new BingMapTileSource(null);
        mBingRoadTileSource.setStyle(BingMapTileSource.IMAGERYSET_ROAD);
        mBingAerialTileSource = new BingMapTileSource(null);
        mBingAerialTileSource.setStyle(BingMapTileSource.IMAGERYSET_AERIAL);
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final Context context;
        final DisplayMetrics dm;

        View v = inflater.inflate(R.layout.fragment_map, container, false);
        Views.inject(this, v);

        context = inflater.getContext();
        dm = context.getResources().getDisplayMetrics();

        final int actionBarHeight;
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true))
        {
            actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data,getResources().getDisplayMetrics());
        }else {
            actionBarHeight = 48;
        }

        mMapView = new MapView(inflater.getContext(), 256, mResourceProxy);
        mMapView.setUseSafeCanvas(true);
        mMapView.setBuiltInZoomControls(true);
        mMapView.setMultiTouchControls(true);
        mMapView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        mPathOverlay = new SolutionPathOverlay(mResourceProxy);
        mMyLocationOverlay = new MyLocationNewOverlay(context, mMyLocationProvider,
                mMapView, mResourceProxy);

        mCompassOverlay = new CompassOverlay(context, new InternalCompassOrientationProvider(context),
                mMapView, mResourceProxy);
        mCompassOverlay.setCompassCenter(25.0f * dm.density, actionBarHeight + 5.0f * dm.density);

        mScaleBarOverlay = new ScaleBarOverlay(context);
        mScaleBarOverlay.setCentred(true);
        mScaleBarOverlay.setScaleBarOffset(dm.widthPixels/2, actionBarHeight + 5.0f * dm.density);

        mMapView.getOverlays().add(mPathOverlay);
        mMapView.getOverlays().add(mScaleBarOverlay);
        mMapView.getOverlays().add(mMyLocationOverlay);
        mMapView.getOverlays().add(mCompassOverlay);

        mMapViewContainer.addView(mMapView, 0);

        return v;
    }

    @Override
    public void onStart() {
        super.onStart();

        // XXX
        mStreamStatusUpdateTimer = new Timer();
        mStreamStatusUpdateTimer.scheduleAtFixedRate(
                new TimerTask() {
                    Runnable updateStatusRunnable = new Runnable() {
                        @Override
                        public void run() {
                            MapFragment.this.updateStatus();
                        }
                    };
                    @Override
                    public void run() {
                        Activity a = getActivity();
                        if (a == null) return;
                        a.runOnUiThread(updateStatusRunnable);
                    }
                }, 200, 2500);
    }

    @Override
    public void onPause() {
        super.onPause();
        saveMapPreferences();
        mMyLocationOverlay.disableMyLocation();
        mCompassOverlay.disableCompass();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.fragment_map, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        final int checked;

        if (mMapView == null) return;

        final String providerName = getTileSourceName();
        if (MAP_MODE_BING_AERIAL.equals(providerName)) {
            checked = R.id.menu_map_mode_bing_aerial;
        }else if (MAP_MODE_BING_ROAD.equals(providerName)) {
            checked = R.id.menu_map_mode_bing_road;
        }else {
            checked = R.id.menu_map_mode_osm;
        }

        menu.findItem(checked).setChecked(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadMapPreferences();
        mMyLocationOverlay.enableMyLocation(mMyLocationProvider);
        mMyLocationOverlay.enableFollowLocation();
        mCompassOverlay.enableCompass(this.mCompassOverlay.getOrientationProvider());
    }

    @Override
    public void onStop() {
        super.onStop();
        //mPathOverlay.clearPath();
        mStreamStatusUpdateTimer.cancel();
        mStreamStatusUpdateTimer = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mMapView = null;
        mPathOverlay = null;
        mMyLocationOverlay = null;
        mCompassOverlay = null;
        mScaleBarOverlay = null;
        Views.reset(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        final String tileSource;

        switch (item.getItemId()) {
        case R.id.menu_map_mode_osm:
            tileSource = TileSourceFactory.MAPNIK.name();
            break;
        case R.id.menu_map_mode_bing_aerial:
            tileSource = MAP_MODE_BING_AERIAL;
            break;
        case R.id.menu_map_mode_bing_road:
            tileSource = MAP_MODE_BING_ROAD;
            break;
        default:
            return super.onOptionsItemSelected(item);
        }

        setTileSource(tileSource);

        return true;
    }

    void updateStatus() {
        MainActivity ma;
        RtkNaviService rtks;
        int serverStatus;

        // XXX
        ma = (MainActivity)getActivity();

        if (ma == null) return;

        rtks = ma.getRtkService();
        if (rtks == null) {
            serverStatus = RtkServerStreamStatus.STATE_CLOSE;
            mStreamStatus.clear();
        }else {
            rtks.getStreamStatus(mStreamStatus);
            rtks.getRtkStatus(mRtkStatus);
            serverStatus = rtks.getServerStatus();
            appendSolutions(rtks.readSolutionBuffer());
            mMyLocationProvider.setStatus(mRtkStatus, !mMapView.isAnimating());
            mGTimeView.setTime(mRtkStatus.getSolution().getTime());
            mSolutionView.setStats(mRtkStatus);
        }

        assertNotNull(mStreamStatus.mMsg);

        mStreamIndicatorsView.setStats(mStreamStatus, serverStatus);
    }

    private void saveMapPreferences() {

        getActivity()
            .getSharedPreferences(SHARED_PREFS_NAME,Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_TITLE_SOURCE, getTileSourceName())
            .putInt(PREFS_SCROLL_X, mMapView.getScrollX())
            .putInt(PREFS_SCROLL_Y, mMapView.getScrollY())
            .putInt(PREFS_ZOOM_LEVEL, mMapView.getZoomLevel())
            .commit();

    }

    private void loadMapPreferences() {
        SharedPreferences prefs = getActivity().getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);

        final String tileSourceName = prefs.getString(PREFS_TITLE_SOURCE, TileSourceFactory.DEFAULT_TILE_SOURCE.name());
        setTileSource(tileSourceName);

        mMapView.getController().setZoom(prefs.getInt(PREFS_ZOOM_LEVEL, 1));

        mMapView.scrollTo(
                prefs.getInt(PREFS_SCROLL_X, 0),
                prefs.getInt(PREFS_SCROLL_Y, 0)
                );
    }

    private void setTileSource(String name) {
        ITileSource tileSource;

        if (MAP_MODE_BING_AERIAL.equals(name)) {
            tileSource = mBingAerialTileSource;
        }else if (MAP_MODE_BING_ROAD.equals(name)) {
            tileSource = mBingRoadTileSource;
        }else {
            try {
                tileSource = TileSourceFactory.getTileSource(name);
            }catch(IllegalArgumentException iae) {
                tileSource = TileSourceFactory.MAPNIK;
            }
        }

        if (!tileSource.equals(mMapView.getTileProvider().getTileSource())) {
            mMapView.setTileSource(tileSource);
        }
    }

    private String getTileSourceName() {
        final ITileSource provider = mMapView.getTileProvider().getTileSource();

        if (MAP_MODE_BING.equals(provider.name())) {
            if (BingMapTileSource.IMAGERYSET_ROAD.equals(((BingMapTileSource)provider).getStyle())) {
                return MAP_MODE_BING_ROAD;
            }else {
                return MAP_MODE_BING_AERIAL;
            }
        }else {
            return provider.name();
        }
    }

    private void appendSolutions(Solution solutions[]) {
        mPathOverlay.addSolutions(solutions);
    }

    MyLocationProvider mMyLocationProvider = new MyLocationProvider();

    static class MyLocationProvider implements IMyLocationProvider {

        private Location mLastLocation = new Location("");
        private boolean mLocationKnown = false;
        private IMyLocationConsumer mConsumer;

        @Override
        public boolean startLocationProvider(
                IMyLocationConsumer myLocationConsumer) {
            mConsumer = myLocationConsumer;
            return true;
        }

        @Override
        public void stopLocationProvider() {
            mConsumer = null;
        }

        @Override
        public Location getLastKnownLocation() {
            return mLocationKnown ? mLastLocation : null;
        }

        private void setSolution(Solution s, boolean notifyConsumer) {
            if (s.getSolutionStatus() == SolutionStatus.NONE) {
                return;
            }

            final Position3d pos = RtkCommon.ecef2pos(s.getPosition());

            mLastLocation.setTime(s.getTime().getUtcTimeMillis());
            mLastLocation.setLatitude(Math.toDegrees(pos.getLat()));
            mLastLocation.setLongitude(Math.toDegrees(pos.getLon()));
            mLastLocation.setAltitude(pos.getHeight());

            mLocationKnown = true;
            if (mConsumer != null) {
                if (notifyConsumer) {
                    mConsumer.onLocationChanged(mLastLocation, this);
                }else {
                    // XXX
                    if (DBG) Log.v(TAG, "onLocationChanged() skipped while animating");
                }
            }
        }

        public void setStatus(RtkControlResult status, boolean notifyConsumer) {
            setSolution(status.getSolution(), notifyConsumer);
        }

    };

}
