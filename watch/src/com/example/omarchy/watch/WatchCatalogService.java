package com.example.omarchy.watch;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Reassembles the app catalog streamed from the phone and stores it locally. */
public class WatchCatalogService extends WearableListenerService {

    private static final String TAG = "OmarchyWatch";
    private static final String CHUNK_PATH = "/omarchy/catalog/chunk";
    private static final String WORKSPACE_STATE_PATH = "/omarchy/workspace/state";
    public static final String ACTION_CATALOG_UPDATED =
            "com.example.omarchy.watch.CATALOG_UPDATED";
    public static final String ACTION_WORKSPACE_UPDATED =
            "com.example.omarchy.watch.WORKSPACE_UPDATED";
    public static final String EXTRA_WORKSPACE = "workspace";
    private static final String CATALOG_FILE = "catalog.json";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (WORKSPACE_STATE_PATH.equals(event.getPath())) {
            byte[] state = event.getData();
            if (state != null && state.length > 0) {
                int workspace = state[0] & 0xff;
                Log.i(TAG, "workspace state from phone: " + workspace);
                Intent update = new Intent(ACTION_WORKSPACE_UPDATED)
                        .setPackage(getPackageName())
                        .putExtra(EXTRA_WORKSPACE, workspace);
                sendBroadcast(update);
            }
            return;
        }
        if (!CHUNK_PATH.equals(event.getPath())) {
            return;
        }
        byte[] data = event.getData();
        if (data == null || data.length < 9) {
            Log.w(TAG, "short catalog chunk: " + (data == null ? -1 : data.length));
            return;
        }
        ByteBuffer header = ByteBuffer.wrap(data, 0, 8).order(ByteOrder.BIG_ENDIAN);
        int version = header.getInt();
        int index = header.getShort() & 0xffff;
        int total = header.getShort() & 0xffff;
        if (total <= 0 || index < 0 || index >= total) {
            Log.w(TAG, "bad catalog chunk header v=" + version
                    + " i=" + index + " total=" + total);
            return;
        }

        File chunkDir = new File(getFilesDir(), "chunks");
        if (!chunkDir.exists() && !chunkDir.mkdirs()) {
            Log.e(TAG, "cannot create chunk directory");
            return;
        }
        String prefix = "catalog-" + version + "-";
        String suffix = "-of-" + total + ".part";
        File chunk = new File(chunkDir, prefix + index + suffix);
        try {
            FileOutputStream out = new FileOutputStream(chunk);
            try {
                out.write(data, 8, data.length - 8);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot write chunk " + chunk.getName() + ": " + e.getMessage());
            return;
        }

        List<File> parts = new ArrayList<>();
        File[] files = chunkDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith(prefix) && name.endsWith(suffix)) {
                    parts.add(file);
                }
            }
        }
        if (parts.size() < total) {
            Log.d(TAG, "catalog v" + version + " chunk " + (index + 1) + "/" + total);
            return;
        }
        Collections.sort(parts, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return chunkIndex(a.getName()) - chunkIndex(b.getName());
            }
        });

        File assembled = new File(getFilesDir(), CATALOG_FILE);
        try {
            FileOutputStream out = new FileOutputStream(assembled);
            try {
                byte[] buffer = new byte[8192];
                for (File part : parts) {
                    java.io.FileInputStream in = new java.io.FileInputStream(part);
                    try {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    } finally {
                        in.close();
                    }
                }
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot assemble catalog: " + e.getMessage());
            return;
        }
        for (File part : parts) {
            // Keep files for the current version only; stale versions are harmless
            // but removing them keeps the directory small.
            if (!part.delete()) {
                Log.w(TAG, "cannot delete " + part.getName());
            }
        }
        Log.i(TAG, "catalog v" + version + " assembled (" + assembled.length() + " bytes)");

        Intent update = new Intent(ACTION_CATALOG_UPDATED).setPackage(getPackageName());
        sendBroadcast(update);
    }

    private static int chunkIndex(String name) {
        String core = name.substring(name.indexOf('-', 8) + 1);
        int end = core.indexOf('-');
        try {
            return Integer.parseInt(end > 0 ? core.substring(0, end) : core);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
