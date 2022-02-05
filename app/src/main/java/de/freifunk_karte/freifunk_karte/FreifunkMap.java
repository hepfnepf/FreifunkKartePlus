package de.freifunk_karte.freifunk_karte;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.DelayedMapListener;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;


public class FreifunkMap extends Activity implements CustomZoomButtonsController.OnZoomListener, MapListener {

    private static final int CLUSTER_SWITCH_LEVEL = 12;
    private static final String MAP_DATA_URI = "https://www.freifunk-karte.de/data.php";
    private static final int ACCESS_FINE_LOCATION_REQUEST = 0x01;
    private static final int ACCESS_STORAGE_REQUEST = 0x02;
    private static final String LOG_TAG = "FreifunkKarte";
    public static final long SET_DATA_DELAY = 200;
    private MapView map;
    private CacheManager cacheManager;
    private boolean hasLocationPermission = false;
    private boolean hasStoragePermission = false;
    private Drawable onlineIcon;
    private Drawable offlineIcon;
    private Bitmap clusterIcon;
    public JSONObject mapData;

    @Override
    public void onVisibilityChanged(boolean b) {

    }

    @Override
    public void onZoom(boolean zoomIn) {
        if (zoomIn) {
            map.getController().zoomIn();
        } else {
            map.getController().zoomOut();
        }
        setMapData(this.mapData);
    }

    @Override
    public boolean onScroll(ScrollEvent event) {
        setMapData(this.mapData);
        return true;
    }

    @Override
    public boolean onZoom(ZoomEvent event) {
        return true;
    }

    private class FetchDataTask extends AsyncTask<String, Integer, Boolean> {
        @Override
        protected Boolean doInBackground(String... params) {
            try {
                Log.d(LOG_TAG, "Processing json result...");
                JSONObject data = FreifunkMap.getJSONObject(params[0]);
                setMapData(data);
                return true;
            } catch (IOException e) {
                return false;
            } catch (JSONException e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            showLoadingProgress(false);
        }
    }

    private boolean isLocationValid(double latitude, double longitude) {
        boolean valid = latitude >= MapView.getTileSystem().getMinLatitude();
        valid = valid & (latitude <= MapView.getTileSystem().getMaxLatitude());
        valid = valid & (longitude >= MapView.getTileSystem().getMinLongitude());
        valid = valid & (longitude <= MapView.getTileSystem().getMaxLongitude());
        return valid;
    }

    private boolean isLocationInArea(double latitude, double longitude) {
        boolean inArea = latitude >= map.getBoundingBox().getLatSouth();
        inArea = inArea & (latitude <= map.getBoundingBox().getLatNorth());
        inArea = inArea & (longitude >= map.getBoundingBox().getLonWest());
        inArea = inArea & (longitude <= map.getBoundingBox().getLonEast());
        return inArea;
    }

    @SuppressLint("MissingPermission")
    public Location getLocation() {
        Location loc = null;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (this.hasLocationPermission) {
            assert lm != null;
            loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }
        return loc;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION },
                    ACCESS_FINE_LOCATION_REQUEST
            );
        } else {
            this.hasLocationPermission = true;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE },
                    ACCESS_STORAGE_REQUEST
            );
        } else {
            this.hasStoragePermission = true;
        }
        this.mapData = new JSONObject();

        this.onlineIcon = getResources().getDrawable(R.mipmap.hotspot);
        this.offlineIcon = getResources().getDrawable(R.mipmap.hotspot_offline);
        BitmapDrawable dr = (BitmapDrawable) getResources().getDrawable(R.mipmap.marker_cluster);
        this.clusterIcon = dr.getBitmap();

        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        setContentView(R.layout.activity_main);
        map = findViewById(R.id.map);
        map.setTileSource(new XYTileSource("Mapnik",
                0, 19, 256, ".png", new String[] {
                "https://a.tile.openstreetmap.de/",
                "https://b.tile.openstreetmap.de/",
                "https://c.tile.openstreetmap.de/" },"© OpenStreetMap contributors",
                new TileSourcePolicy(12,
                                TileSourcePolicy.FLAG_NO_PREVENTIVE
                                | TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
                                | TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
                ))
        );
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT);
        map.getZoomController().setOnZoomListener(this);
        // map.setOnLongClickListener(this);
        map.setMultiTouchControls(true);

        this.cacheManager = new CacheManager(map);

        IMapController mapController = map.getController();
        mapController.setZoom(16.0);
        Location startLocation = this.getLocation();
        GeoPoint startPoint;
        if (startLocation != null) {
            startPoint = new GeoPoint(startLocation);
        } else {
            SharedPreferences sharedPreferences = getSharedPreferences("StoreLocation", MODE_PRIVATE);
            double latitude =  sharedPreferences.getFloat("Latitude",(float)52.520007);
            double longitude = sharedPreferences.getFloat("Longitude", (float)13.404954);
            startPoint = new GeoPoint(latitude, longitude);
        }
        mapController.setCenter(startPoint);

        ImageButton centerMap = findViewById(R.id.center_button);
        centerMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Location currentLocation = getLocation();
                if (currentLocation == null) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(v.getContext());
                    builder.setTitle(R.string.location_alert_title)
                            .setMessage(R.string.location_alert_message)
                            .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.dismiss();
                                }
                            });
                    builder.create().show();
                } else {
                    GeoPoint currentPosition = new GeoPoint(currentLocation);
                    map.getController().animateTo(currentPosition);
                }
            }
        });

        GpsMyLocationProvider locationProvider = new GpsMyLocationProvider(this.getApplicationContext());
        MyLocationNewOverlay locationOverlay = new MyLocationNewOverlay(locationProvider, map);
        locationOverlay.enableMyLocation();
        map.getOverlays().add(locationOverlay);

        map.addMapListener(new DelayedMapListener(this, SET_DATA_DELAY));
        showLoadingProgress(true);
        new FetchDataTask().execute(MAP_DATA_URI);
    }

    @Override
    public void onResume() {
        super.onResume();
        map.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        map.onPause();
    }

    @Override
    public  void onStop(){
        Location currentLocation = getLocation();
        if (currentLocation!=null){
            float latitude = (float) currentLocation.getLatitude();
            float longitude = (float) currentLocation.getLongitude();
            SharedPreferences sharedPreferences = getSharedPreferences("StoreLocation", MODE_PRIVATE);
            SharedPreferences.Editor preferenceEditor= sharedPreferences.edit();
            preferenceEditor.putFloat("Latitude",latitude);
            preferenceEditor.putFloat("Longitude",longitude);
            preferenceEditor.commit();
        }

        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        if (requestCode == ACCESS_FINE_LOCATION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                this.hasLocationPermission = true;
            }
        }

        if (requestCode == ACCESS_STORAGE_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                this.hasStoragePermission = true;
            }
        }
    }

    public static JSONObject getJSONObject(String urlString) throws IOException, JSONException {

        HttpURLConnection urlConnection;
        URL url = new URL(urlString);

        urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestMethod("GET");
        urlConnection.setReadTimeout(10000);
        urlConnection.setConnectTimeout(15000);
        urlConnection.setDoOutput(true);
        urlConnection.connect();

        BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();

        String jsonString = sb.toString();
        Log.d(LOG_TAG, "Router data fetched successfully...");
        urlConnection.disconnect();

        return new JSONObject(jsonString);
    }

    public void showLoadingProgress(boolean isLoading) {
        ProgressBar spinner = findViewById(R.id.loadDataProgress);
        if (isLoading) {
            spinner.setVisibility(View.VISIBLE);
        } else {
            spinner.setVisibility(View.INVISIBLE);
        }
    }

    public void setMapData(JSONObject data) {
        this.mapData = data;
        this.map.getOverlays().retainAll(this.map.getOverlays().subList(0, 1));

        RadiusMarkerClusterer routerMarkers = new RadiusMarkerClusterer(this);
        routerMarkers.setIcon(this.clusterIcon);
        routerMarkers.getTextPaint().setColor(Color.DKGRAY);
        routerMarkers.mAnchorU = Marker.ANCHOR_CENTER;
        routerMarkers.mAnchorV = Marker.ANCHOR_CENTER;
        routerMarkers.setMaxClusteringZoomLevel(CLUSTER_SWITCH_LEVEL);
        if(map.getZoomLevelDouble() > 12.0) {
            routerMarkers.setRadius(100);
        } else if(map.getZoomLevelDouble() > 7.0) {
            routerMarkers.setRadius(150);
        } else {
            routerMarkers.setRadius(200);
        }
        this.map.getOverlays().add(routerMarkers);

        if (mapData.length() > 0) {
            try {
                JSONArray routers = this.mapData.getJSONArray("allTheRouters");
                for (int i = 0; i < routers.length(); i++) {
                    JSONObject router = routers.getJSONObject(i);
                    try {
                        double lat = Double.parseDouble(router.getString("lat"));
                        double lon = Double.parseDouble(router.getString("long"));
                        if (isLocationValid(lat, lon) && isLocationInArea(lat, lon)) {
                            String id = router.getString("id");
                            String name = router.getString("name");
                            String status = router.getString("status");
                            String community = router.getString("community");
                            Marker routerMarker = new Marker(this.map);
                            routerMarker.setPosition(new GeoPoint(lat, lon));
                            if (status.equals("online")) {
                                routerMarker.setIcon(this.onlineIcon);
                            } else {
                                routerMarker.setIcon(this.offlineIcon);
                            }
                            routerMarker.setTitle(name);
                            routerMarker.setId(id);
                            routerMarker.setSnippet(status);
                            routerMarker.setSubDescription(
                                    "Community: ".concat(community)
                                    .concat("<br />")
                                    .concat(getResources().getString(R.string.marker_snippet))
                                    .concat("<br />")
                                    .concat(getResources().getString(R.string.bubble_snippet))
                            );
                            routerMarker.setVisible(true);
                            routerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                            routerMarker.setOnMarkerClickListener(new Marker.OnMarkerClickListener() {
                                @Override
                                public boolean onMarkerClick(Marker marker, MapView mapView) {
                                    if (marker.isInfoWindowShown()) {
                                        double lat = marker.getPosition().getLatitude();
                                        double lon = marker.getPosition().getLongitude();
                                        String name = "(".concat(marker.getTitle()).concat(")");
                                        Uri route = Uri.parse("geo:0,0?q="
                                                .concat(Double.toString(lat))
                                                .concat(",")
                                                .concat(Double.toString(lon))
                                                .concat(name));
                                        Intent navIntent = new Intent(Intent.ACTION_VIEW, route);
                                        marker.closeInfoWindow();
                                        startActivity(navIntent);
                                    } else {
                                        marker.showInfoWindow();
                                    }
                                    return true;
                                }
                            });
                            routerMarkers.add(routerMarker);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        this.map.invalidate();
    }
}