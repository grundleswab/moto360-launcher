package com.example.omarchy.watch;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Centered icon-only app launcher with workspace side zones. */
public class MainActivity extends Activity {

    private static final String TAG = "OmarchyWatch";
    private static final String CATALOG_REQUEST_PATH = "/omarchy/catalog/request";
    private static final String LAUNCH_PATH = "/omarchy/launch";
    private static final String WORKSPACE_PATH = "/omarchy/workspace";
    private static final String FOCUS_PATH = "/omarchy/focus";
    private static final String CLOSE_PATH = "/omarchy/close";
    private static final String CATALOG_FILE = "catalog.json";
    private static final int DEFAULT_WORKSPACE_COUNT = 5;

    private GoogleApiClient mGoogleApiClient;
    private GridView mAppGrid;
    private AppAdapter mAdapter;
    private TextView mWorkspaceLabel;
    private final List<AppEntry> mApps = new ArrayList<AppEntry>();
    private int mWorkspace = 1;
    private int mWorkspaceCount = DEFAULT_WORKSPACE_COUNT;
    private boolean mCatalogReceiverRegistered;
    private boolean mWorkspaceReceiverRegistered;

    private final BroadcastReceiver mCatalogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "catalog update broadcast received");
            loadCatalog();
        }
    };

    private final BroadcastReceiver mWorkspaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int workspace = intent.getIntExtra(
                    WatchCatalogService.EXTRA_WORKSPACE, mWorkspace);
            Log.i(TAG, "workspace update broadcast: " + workspace);
            setWorkspace(workspace);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A dock pickup can arrive while the screen is asleep.  Make sure the
        // window turns the screen on and dismisses any keyguard on the way in.
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        setContentView(R.layout.main);

        mAppGrid = (GridView) findViewById(R.id.app_grid);
        mAppGrid.setEmptyView(findViewById(R.id.empty_status));
        mAdapter = new AppAdapter(this, mApps);
        mAppGrid.setAdapter(mAdapter);
        mAppGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppEntry app = mApps.get(position);
                Log.i(TAG, "tap app: " + app.id);
                sendLaunch(app.id);
            }
        });

        mWorkspaceLabel = (TextView) findViewById(R.id.workspace_label);

        // Left zone: cycle focus above close app.
        findViewById(R.id.btn_focus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "focus cycle button");
                sendFocus();
            }
        });
        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "close app button");
                sendClose();
            }
        });

        // Right zone: up goes back, down advances through workspaces.
        findViewById(R.id.btn_workspace_up).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleWorkspace(-1);
            }
        });
        findViewById(R.id.btn_workspace_down).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleWorkspace(1);
            }
        });

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle bundle) {
                        Log.i(TAG, "GMS connected");
                        requestCatalog();
                    }

                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.i(TAG, "GMS suspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        Log.e(TAG, "GMS connection failed: " + result.getErrorCode());
                    }
                })
                .build();

        setWorkspace(mWorkspace);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // A catalog update may have arrived while this activity was stopped
        // (and its receiver unregistered).  Always reload the saved catalog
        // on the way in so the grid is never stale.
        loadCatalog();
        registerReceiver(mCatalogReceiver,
                new IntentFilter(WatchCatalogService.ACTION_CATALOG_UPDATED));
        mCatalogReceiverRegistered = true;
        registerReceiver(mWorkspaceReceiver,
                new IntentFilter(WatchCatalogService.ACTION_WORKSPACE_UPDATED));
        mWorkspaceReceiverRegistered = true;
        mGoogleApiClient.connect();
    }

    @Override
    protected void onStop() {
        if (mCatalogReceiverRegistered) {
            unregisterReceiver(mCatalogReceiver);
            mCatalogReceiverRegistered = false;
        }
        if (mWorkspaceReceiverRegistered) {
            unregisterReceiver(mWorkspaceReceiver);
            mWorkspaceReceiverRegistered = false;
        }
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        super.onStop();
    }

    private void loadCatalog() {
        File catalog = new File(getFilesDir(), CATALOG_FILE);
        List<AppEntry> loaded = new ArrayList<AppEntry>();
        if (catalog.exists()) {
            try {
                byte[] bytes = new byte[(int) catalog.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(catalog);
                try {
                    int offset = 0;
                    while (offset < bytes.length) {
                        int read = in.read(bytes, offset, bytes.length - offset);
                        if (read < 0) {
                            break;
                        }
                        offset += read;
                    }
                } finally {
                    in.close();
                }
                JSONObject root = new JSONObject(new String(bytes, Charset.forName("UTF-8")));
                JSONArray apps = root.getJSONArray("apps");
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject item = apps.getJSONObject(i);
                    String id = item.getString("id");
                    String name = item.optString("name", id);
                    String icon = item.optString("icon", "");
                    loaded.add(new AppEntry(id, name, icon));
                }
                int count = root.optInt("workspace_count", mWorkspaceCount);
                mWorkspaceCount = count > 0 ? count : DEFAULT_WORKSPACE_COUNT;
                setWorkspace(root.optInt("workspace", mWorkspace));
                Log.i(TAG, "loaded " + loaded.size() + " apps from catalog v"
                        + root.optInt("version", -1));
            } catch (Exception e) {
                Log.e(TAG, "cannot read catalog: " + e.getMessage());
            }
        }
        mApps.clear();
        mApps.addAll(loaded);
        mAdapter.notifyDataSetChanged();
    }

    private void cycleWorkspace(int delta) {
        int target = ((mWorkspace - 1 + delta + mWorkspaceCount) % mWorkspaceCount) + 1;
        Log.i(TAG, "workspace zone delta=" + delta + " target=" + target);
        setWorkspace(target);
        sendWorkspace(target);
    }

    private void setWorkspace(int workspace) {
        if (workspace < 1 || workspace > mWorkspaceCount) {
            return;
        }
        mWorkspace = workspace;
        mWorkspaceLabel.setText(workspace + " / " + mWorkspaceCount);
    }

    private void requestCatalog() {
        sendToWatchNode(CATALOG_REQUEST_PATH, new byte[0]);
    }

    private void sendLaunch(String desktopId) {
        byte[] payload = desktopId.getBytes(Charset.forName("UTF-8"));
        sendToWatchNode(LAUNCH_PATH, payload);
    }

    private void sendWorkspace(int workspace) {
        sendToWatchNode(WORKSPACE_PATH, new byte[] {(byte) workspace});
    }

    private void sendFocus() {
        sendToWatchNode(FOCUS_PATH, new byte[0]);
    }

    private void sendClose() {
        sendToWatchNode(CLOSE_PATH, new byte[0]);
    }

    private void sendToWatchNode(final String path, final byte[] payload) {
        if (mGoogleApiClient == null || !mGoogleApiClient.isConnected()) {
            Log.w(TAG, "phone not connected; cannot send " + path);
            Toast.makeText(this, "Phone not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                .setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                    @Override
                    public void onResult(NodeApi.GetConnectedNodesResult result) {
                        for (Node node : result.getNodes()) {
                            Wearable.MessageApi.sendMessage(
                                    mGoogleApiClient, node.getId(), path, payload)
                                    .setResultCallback(
                                            new ResultCallback<MessageApi.SendMessageResult>() {
                                                @Override
                                                public void onResult(
                                                        MessageApi.SendMessageResult sendResult) {
                                                    Log.i(TAG, "send " + path + " result: "
                                                            + sendResult.getStatus().isSuccess());
                                                }
                                            });
                        }
                    }
                });
    }

    static class AppEntry {
        final String id;
        final String name;
        final String icon;
        Bitmap bitmap;
        boolean bitmapLoaded;

        AppEntry(String id, String name, String icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }

        Bitmap getBitmap() {
            if (!bitmapLoaded) {
                bitmapLoaded = true;
                if (icon != null && icon.length() > 0) {
                    try {
                        byte[] bytes = Base64.decode(icon, Base64.DEFAULT);
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    } catch (Exception ignored) {
                    }
                }
            }
            return bitmap;
        }
    }

    private static class AppAdapter extends ArrayAdapter<AppEntry> {
        AppAdapter(Context context, List<AppEntry> apps) {
            super(context, R.layout.app_icon, apps);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View iconView = convertView;
            if (iconView == null) {
                LayoutInflater inflater = LayoutInflater.from(getContext());
                iconView = inflater.inflate(R.layout.app_icon, parent, false);
            }
            AppEntry app = getItem(position);
            ImageView icon = (ImageView) iconView.findViewById(R.id.app_icon);
            Bitmap bitmap = app.getBitmap();
            if (bitmap != null) {
                icon.setImageBitmap(bitmap);
            } else {
                icon.setImageResource(android.R.drawable.ic_menu_help);
            }
            return iconView;
        }
    }
}
