package com.example.omarchy.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Opens the launcher when the watch is lifted off its wireless charging dock.
 *
 * The Moto 360 Gen 1 charges wirelessly, so Android reports it as
 * BATTERY_PLUGGED_WIRELESS and emits ACTION_POWER_DISCONNECTED the instant the
 * watch leaves the dock.  A manifest-registered receiver catches that even
 * when the app is not running -- no root, gesture, or sensor sampling needed.
 */
public class DockPickupReceiver extends BroadcastReceiver {

    private static final String TAG = "OmarchyWatch";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            return;
        }
        Log.i(TAG, "dock pickup (power disconnected) -- launching launcher");

        // The watch may have been deeply asleep on the dock.  Keep the CPU
        // awake briefly so startActivity() is not frozen mid-flight.
        try {
            PowerManager pm =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "OmarchyWatch:dock_pickup");
            wakeLock.acquire(10000L);
        } catch (Exception ignored) {
            // A wake lock is a best-effort aid; launching still works without.
        }

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }
}
