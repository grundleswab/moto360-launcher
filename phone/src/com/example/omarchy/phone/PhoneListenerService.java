package com.example.omarchy.phone;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.Charset;

/** Receives commands from the watch and forwards them to the host bridge. */
public class PhoneListenerService extends WearableListenerService {

    private static final String TAG = "OmarchyBridge";
    private static final String CATALOG_REQUEST_PATH = "/omarchy/catalog/request";
    private static final String LAUNCH_PATH = "/omarchy/launch";
    private static final String WORKSPACE_PATH = "/omarchy/workspace";
    private static final String FOCUS_PATH = "/omarchy/focus";
    private static final String CLOSE_PATH = "/omarchy/close";

    @Override
    public void onCreate() {
        super.onCreate();
        // Ensure the TCP bridge is alive whenever Wear delivers something.
        startService(new Intent(this, PhoneBridgeService.class));
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        String path = event.getPath();
        Log.i(TAG, "message from watch: " + path);
        if (CATALOG_REQUEST_PATH.equals(path)) {
            PhoneBridgeService.requestCatalog();
        } else if (LAUNCH_PATH.equals(path)) {
            String id = new String(event.getData(), Charset.forName("UTF-8")).trim();
            Log.i(TAG, "launch request: " + id);
            PhoneBridgeService.sendLaunch(id);
        } else if (WORKSPACE_PATH.equals(path)) {
            byte[] data = event.getData();
            int workspace = (data == null || data.length == 0) ? -1 : data[0] & 0xff;
            Log.i(TAG, "workspace request: " + workspace);
            PhoneBridgeService.sendWorkspace(workspace);
        } else if (FOCUS_PATH.equals(path)) {
            Log.i(TAG, "focus cycle request");
            PhoneBridgeService.sendFocus();
        } else if (CLOSE_PATH.equals(path)) {
            Log.i(TAG, "close app request");
            PhoneBridgeService.sendClose();
        }
    }
}
