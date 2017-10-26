package com.tylorbayer.globefundme;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

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
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.OptionalPendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static android.graphics.Color.CYAN;
import static android.graphics.Color.LTGRAY;
import static android.view.Gravity.START;

public class MainActivity extends AppCompatActivity {
    private MapView map;
    private PopupContainer popupContainer;
    private PopupDialog popupDialog;
    private ProgressDialog progressDialog;
    private AtomicInteger count;
    private GoogleApiClient mGoogleApiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();

        mGoogleApiClient = new GoogleApiClient.Builder(this).addApi(Auth.GOOGLE_SIGN_IN_API,gso).build();

        mGoogleApiClient.connect();

        LayoutInflater layoutInflater = (LayoutInflater) getBaseContext().getSystemService(LAYOUT_INFLATER_SERVICE);
        final View popupView = layoutInflater.inflate(R.layout.popup, null);

        final PopupWindow popupWindow = new PopupWindow(popupView, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        map = (MapView) findViewById(R.id.mapView);
        Layer[] layers = map.getLayers();
            for (Layer layer : layers) {
                Log.d("Debug", layer.getName());
                if (!layer.getName().equals("Layers")) {
                    if (!layer.isInitialized())
                        continue;
                    else {
                        layer.setVisible(false);
                    }
                }
            }

        // Tap on the map and show popups for selected features.
        map.setOnSingleTapListener(new OnSingleTapListener() {
            private static final long serialVersionUID = 1L;

            public void onSingleTap(float x, float y) {
                if (map.isLoaded()) {
                    popupWindow.dismiss();
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
                            if (featureLayer.getPopupInfo() != null && featureLayer.isVisible() == true) {
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

        final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                if (map.isLoaded()) {
//                    Layer[] layers = map.getLayers();
//                    for (Layer layer : layers) {
//                        Log.d("Debug", layer.getName());
//                        if (!layer.getName().equals("Layers")) {
//                            if (!layer.isInitialized())
//                                continue;
//                            else if (!layer.isVisible())
//                                layer.setVisible(true);
//                            else {
//                                layer.setVisible(false);
//                            }
//                        }
//                    }
//                }
                if (popupWindow.isShowing() == false) {

                    final Button btnCW = popupView.findViewById(R.id.cw);
                    final Button btnEDU = popupView.findViewById(R.id.edu);
                    final Button btnENV = popupView.findViewById(R.id.env);
                    final Button btnDR = popupView.findViewById(R.id.dr);
                    final Button btnHE = popupView.findViewById(R.id.he);
                    final Button btnZeren = popupView.findViewById(R.id.zeren);
                    final Button btnQuakes = popupView.findViewById(R.id.quakes);
                    final Button btnHuman = popupView.findViewById(R.id.human);
                    final Button btnliteracy = popupView.findViewById(R.id.literacy);
                    final Button btnNourish = popupView.findViewById(R.id.nourish);

                    btnCW.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(10).isVisible() == true) {
                                map.getLayer(10).setVisible(false);
                                btnCW.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(10).setVisible(true);
                                btnCW.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnEDU.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(9).isVisible() == true) {
                                map.getLayer(9).setVisible(false);
                                btnEDU.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(9).setVisible(true);
                                btnEDU.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnENV.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(8).isVisible() == true) {
                                map.getLayer(8).setVisible(false);
                                btnENV.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(8).setVisible(true);
                                btnENV.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnDR.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(7).isVisible() == true) {
                                map.getLayer(7).setVisible(false);
                                btnDR.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(7).setVisible(true);
                                btnDR.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnHE.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(6).isVisible() == true) {
                                map.getLayer(6).setVisible(false);
                                btnHE.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(6).setVisible(true);
                                btnHE.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnZeren.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(5).isVisible() == true) {
                                map.getLayer(5).setVisible(false);
                                btnZeren.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(5).setVisible(true);
                                btnZeren.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnQuakes.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(4).isVisible() == true) {
                                map.getLayer(4).setVisible(false);
                                btnQuakes.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(4).setVisible(true);
                                btnQuakes.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnHuman.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(3).isVisible() == true) {
                                map.getLayer(3).setVisible(false);
                                btnHuman.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(3).setVisible(true);
                                btnHuman.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnliteracy.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(2).isVisible() == true) {
                                map.getLayer(2).setVisible(false);
                                btnliteracy.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(2).setVisible(true);
                                btnliteracy.setBackgroundColor(CYAN);
                            }
                        }
                    });
                    btnNourish.setOnClickListener(new Button.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // TODO Auto-generated method stub
                            if (map.getLayer(1).isVisible() == true) {
                                map.getLayer(1).setVisible(false);
                                btnNourish.setBackgroundColor(LTGRAY);
                            } else {
                                map.getLayer(1).setVisible(true);
                                btnNourish.setBackgroundColor(CYAN);
                            }
                        }
                    });

                    popupWindow.showAtLocation(map, START, 0, 90);
                }
                else {
                    popupWindow.dismiss();
                }
            }
        });
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
        if (id == R.id.action_logout) {
            Auth.GoogleSignInApi.signOut(mGoogleApiClient).setResultCallback(
                    new ResultCallback<Status>() {
                        @Override
                        public void onResult(Status status) {
                            Intent login = new Intent(MainActivity.this, LoginActivity.class);
                            startActivity(login);
                            finish();
                            mGoogleApiClient.disconnect();
                        }
                    });

            return true;
        }
        else{
            return false;
        }
    }

    public MapView getMap() {
        return map;
    }
}
