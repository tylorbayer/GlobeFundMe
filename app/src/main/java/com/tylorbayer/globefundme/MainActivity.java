package com.tylorbayer.globefundme;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.View;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.esri.android.map.Layer;
import com.esri.android.map.MapView;
import com.esri.android.map.ags.ArcGISDynamicMapServiceLayer;
import com.esri.android.map.ags.ArcGISFeatureLayer;
import com.esri.android.map.ags.ArcGISLayerInfo;
import com.esri.android.map.event.OnSingleTapListener;
import com.esri.android.map.popup.Popup;
import com.esri.android.map.popup.PopupContainer;
import com.esri.core.geometry.Envelope;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.map.Feature;
import com.esri.core.map.FeatureSet;
import com.esri.core.map.popup.PopupInfo;
import com.esri.core.tasks.ags.query.Query;
import com.esri.core.tasks.ags.query.QueryTask;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private MapView map;
    private PopupContainer popupContainer;
    private PopupDialog popupDialog;
    private ProgressDialog progressDialog;
    private AtomicInteger count;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

//        final MapView map = new MapView(this, "http://pm.maps.arcgis.com/home/item.html?id=704b853ddee54ac489e63ec821ceaa9b", "tbayer_PM", "lasvegas11");
//        setContentView(map);

        setContentView(R.layout.activity_main);

        map = (MapView) findViewById(R.id.mapView);

        // Tap on the map and show popups for selected features.
        map.setOnSingleTapListener(new OnSingleTapListener() {
            private static final long serialVersionUID = 1L;

            public void onSingleTap(float x, float y) {
                if (map.isLoaded()) {
                    // Instantiate a PopupContainer
                    popupContainer = new PopupContainer(map);
                    int id = popupContainer.hashCode();
                    popupDialog = null;
                    // Display spinner.
                    if (progressDialog == null || !progressDialog.isShowing())
                        progressDialog = ProgressDialog.show(map.getContext(), "", "Querying...");

                    // Loop through each layer in the webmap
                    int tolerance = 20;
                    Envelope env = new Envelope(map.toMapPoint(x, y), 20 * map.getResolution(), 20 * map.getResolution());
                    Layer[] layers = map.getLayers();
                    count = new AtomicInteger();
                    for (Layer layer : layers) {
                        // If the layer has not been initialized or is invisible, do nothing.
                        if (!layer.isInitialized() || !layer.isVisible())
                            continue;

                        if (layer instanceof ArcGISFeatureLayer) {
                            // Query feature layer and display popups
                            ArcGISFeatureLayer featureLayer = (ArcGISFeatureLayer) layer;
                            if (featureLayer.getPopupInfo() != null) {
                                // Query feature layer which is associated with a popup definition.
                                count.incrementAndGet();
                                new RunQueryFeatureLayerTask(x, y, tolerance, id).execute(featureLayer);
                            }
                        }
                        else if (layer instanceof ArcGISDynamicMapServiceLayer) {
                            // Query dynamic map service layer and display popups.
                            ArcGISDynamicMapServiceLayer dynamicLayer = (ArcGISDynamicMapServiceLayer) layer;
                            // Retrieve layer info for each sub-layer of the dynamic map service layer.
                            ArcGISLayerInfo[] layerinfos = dynamicLayer.getAllLayers();
                            if (layerinfos == null)
                                continue;

                            // Loop through each sub-layer
                            for (ArcGISLayerInfo layerInfo : layerinfos) {
                                // Obtain PopupInfo for sub-layer.
                                PopupInfo popupInfo = dynamicLayer.getPopupInfo(layerInfo.getId());
                                // Skip sub-layer which is without a popup definition.
                                if (popupInfo == null) {
                                    continue;
                                }
                                // Check if a sub-layer is visible.
                                ArcGISLayerInfo info = layerInfo;
                                while ( info != null && info.isVisible() ) {
                                    info = info.getParentLayer();
                                }
                                // Skip invisible sub-layer
                                if ( info != null && ! info.isVisible() ) {
                                    continue;
                                };

                                // Check if the sub-layer is within the scale range
                                double maxScale = (layerInfo.getMaxScale() != 0) ? layerInfo.getMaxScale():popupInfo.getMaxScale();
                                double minScale = (layerInfo.getMinScale() != 0) ? layerInfo.getMinScale():popupInfo.getMinScale();

                                if ((maxScale == 0 || map.getScale() > maxScale) && (minScale == 0 || map.getScale() < minScale)) {
                                    // Query sub-layer which is associated with a popup definition and is visible and in scale range.
                                    count.incrementAndGet();
                                    new RunQueryDynamicLayerTask(env, layer, layerInfo.getId(), dynamicLayer.getSpatialReference(), id).execute(dynamicLayer.getUrl() + "/" + layerInfo.getId());
                                }
                            }
                        }
                    }
                }
            }
        });

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);


        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (map.isLoaded()) {
                    Layer[] layers = map.getLayers();
                    for (Layer layer : layers) {
                        Log.d("Debug", layer.getName());
                        if (!layer.getName().equals("Layers")) {
                            if (!layer.isInitialized())
                                continue;
                            else if (!layer.isVisible())
                                layer.setVisible(true);
                            else {
                                layer.setVisible(false);
                            }
                        }
                    }
                }
            }
        });

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
            this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

//        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
//        navigationView.setNavigationItemSelectedListener(this);
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("StatementWithEmptyBody")
//    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_camera) {
            // Handle the camera action
        } else if (id == R.id.nav_gallery) {

        } else if (id == R.id.nav_slideshow) {

        } else if (id == R.id.nav_manage) {

        } else if (id == R.id.nav_share) {

        } else if (id == R.id.nav_send) {

        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private void createPopupViews(Feature[] features, final int id) {
        if ( id != popupContainer.hashCode() ) {
            if (progressDialog != null && progressDialog.isShowing() && count.intValue() == 0)
                progressDialog.dismiss();

            return;
        }

        if (popupDialog == null) {
            if (progressDialog != null && progressDialog.isShowing())
                progressDialog.dismiss();

            // Create a dialog for the popups and display it.
            popupDialog = new PopupDialog(map.getContext(), popupContainer);
            popupDialog.show();
        }
    }

    // Query feature layer by hit test
    private class RunQueryFeatureLayerTask extends AsyncTask<ArcGISFeatureLayer, Void, Feature[]> {

        private int tolerance;
        private float x;
        private float y;
        private ArcGISFeatureLayer featureLayer;
        private int id;

        public RunQueryFeatureLayerTask(float x, float y, int tolerance, int id) {
            super();
            this.x = x;
            this.y = y;
            this.tolerance = tolerance;
            this.id = id;
        }

        @Override
        protected Feature[] doInBackground(ArcGISFeatureLayer... params) {
            for (ArcGISFeatureLayer featureLayer : params) {
                this.featureLayer = featureLayer;
                // Retrieve feature ids near the point.
                int[] ids = featureLayer.getGraphicIDs(x, y, tolerance);
                if (ids != null && ids.length > 0) {
                    ArrayList<Feature> features = new ArrayList<Feature>();
                    for (int id : ids) {
                        // Obtain feature based on the id.
                        Feature f = featureLayer.getGraphic(id);
                        if (f == null)
                            continue;
                        features.add(f);
                    }
                    // Return an array of features near the point.
                    return features.toArray(new Feature[0]);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Feature[] features) {
            count.decrementAndGet();
            if (features == null || features.length == 0) {
                if (progressDialog != null && progressDialog.isShowing() && count.intValue() == 0)
                    progressDialog.dismiss();

                return;
            }
            // Check if the requested PopupContainer id is the same as the current PopupContainer.
            // Otherwise, abandon the obsoleted query result.
            if ( id != popupContainer.hashCode() ) {
                if (progressDialog != null && progressDialog.isShowing() && count.intValue() == 0)
                    progressDialog.dismiss();

                return;
            }

            for (Feature fr : features) {
                Popup popup = featureLayer.createPopup(map, 0, fr);
                popupContainer.addPopup(popup);
            }
            createPopupViews(features, id);
        }

    }

    // Query dynamic map service layer by QueryTask
    private class RunQueryDynamicLayerTask extends AsyncTask<String, Void, FeatureSet> {
        private Envelope env;
        private SpatialReference sr;
        private int id;
        private Layer layer;
        private int subLayerId;

        public RunQueryDynamicLayerTask(Envelope env, Layer layer, int subLayerId, SpatialReference sr, int id) {
            super();
            this.env = env;
            this.sr = sr;
            this.id = id;
            this.layer = layer;
            this.subLayerId = subLayerId;
        }

        @Override
        protected FeatureSet doInBackground(String... urls) {
            for (String url : urls) {
                // Retrieve features within the envelope.
                Query query = new Query();
                query.setInSpatialReference(sr);
                query.setOutSpatialReference(sr);
                query.setGeometry(env);
                query.setMaxFeatures(10);
                query.setOutFields(new String[] { "*" });

                QueryTask queryTask = new QueryTask(url);
                try {
                    FeatureSet results = queryTask.execute(query);
                    return results;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(final FeatureSet result) {
            count.decrementAndGet();
            if (result == null) {
                if (progressDialog != null && progressDialog.isShowing() && count.intValue() == 0)
                    progressDialog.dismiss();

                return;
            }

            Feature[] features = result.getGraphics();
            if (features == null || features.length == 0) {
                if (progressDialog != null && progressDialog.isShowing() && count.intValue() == 0)
                    progressDialog.dismiss();

                return;
            }
            // Check if the requested PopupContainer id is the same as the current PopupContainer.
            // Otherwise, abandon the obsoleted query result.
            if (id != popupContainer.hashCode()) {
                // Dismiss spinner
                if (progressDialog != null && progressDialog.isShowing() && count.intValue() == 0)
                    progressDialog.dismiss();

                return;
            }
            PopupInfo popupInfo = layer.getPopupInfo(subLayerId);
            if (popupInfo == null) {
                // Dismiss spinner
                if (progressDialog != null && progressDialog.isShowing() && count.intValue() == 0)
                    progressDialog.dismiss();

                return;
            }

            for (Feature fr : features) {
                Popup popup = layer.createPopup(map, subLayerId, fr);
                popupContainer.addPopup(popup);
            }
            createPopupViews(features, id);

        }
    }

    // A customize full screen dialog.
    private class PopupDialog extends Dialog {
        private PopupContainer popupContainer;

        public PopupDialog(Context context, PopupContainer popupContainer) {
            super(context, android.R.style.Theme);
            this.popupContainer = popupContainer;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            LinearLayout layout = new LinearLayout(getContext());
            layout.addView(popupContainer.getPopupContainerView(), android.widget.LinearLayout.LayoutParams.FILL_PARENT, android.widget.LinearLayout.LayoutParams.FILL_PARENT);
            setContentView(layout, params);
        }

    }
}
