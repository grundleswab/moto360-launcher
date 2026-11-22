package com.example.omarchy.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Starts the persistent host bridge. */
public class MainActivity extends Activity {

    private static final String TAG = "OmarchyBridge";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity created; starting bridge");
        startService(new Intent(this, PhoneBridgeService.class));
        finish();
    }
}
