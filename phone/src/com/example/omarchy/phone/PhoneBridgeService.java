package com.example.omarchy.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the TCP link to the Omarchy host open and relays the app catalog to the
 * watch and launch requests back to the host.
 */
public class PhoneBridgeService extends Service {

    private static final String TAG = "OmarchyBridge";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9999;
    private static final String CATALOG_CHUNK_PATH = "/omarchy/catalog/chunk";
    private static final String WORKSPACE_STATE_PATH = "/omarchy/workspace/state";
    private static final int CHUNK_SIZE = 16000;
    private static final int NOTIFICATION_ID = 4321;

    private static volatile PhoneBridgeService sInstance;

    private final Object mOutputLock = new Object();
    private final ExecutorService mWatchSender = Executors.newSingleThreadExecutor();
    private volatile Socket mSocket;
    private volatile OutputStream mOutput;
    private volatile int mCatalogVersion = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForegroundNotification();
        new Thread(new Runnable() {
            @Override
            public void run() {
                hostLoop();
            }
        }, "omarchy-host-link").start();
        Log.i(TAG, "PhoneBridgeService started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        closeSocket();
        mWatchSender.shutdownNow();
        super.onDestroy();
    }

    private void startForegroundNotification() {
        String channelId = "omarchy_watch_bridge";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Watch bridge", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Notification notification;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            notification = new Notification.Builder(this, channelId)
                    .setContentTitle("Omarchy watch bridge")
                    .setContentText("Connected to Omarchy")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("Omarchy watch bridge")
                    .setContentText("Connected to Omarchy")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .build();
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void closeSocket() {
        Socket socket = mSocket;
        mSocket = null;
        mOutput = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void hostLoop() {
        while (true) {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST, PORT), 5000);
                socket.setKeepAlive(true);
                mSocket = socket;
                mOutput = socket.getOutputStream();
                Log.i(TAG, "connected to host " + socket.getRemoteSocketAddress());

                // Ask the host for the current catalog on every (re)connect.
                sendLine("{\"type\":\"catalog_request\"}");

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    handleHostLine(line);
                }
                Log.i(TAG, "host closed connection");
            } catch (Exception e) {
                Log.e(TAG, "host link error: " + e.getMessage());
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
                if (mSocket == socket) {
                    mSocket = null;
                    mOutput = null;
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sendLine(String line) {
        OutputStream out = mOutput;
        if (out == null) {
            Log.w(TAG, "no host socket; dropping " + line);
            return;
        }
        byte[] bytes = (line + "\n").getBytes(java.nio.charset.Charset.forName("UTF-8"));
        synchronized (mOutputLock) {
            try {
                out.write(bytes);
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "host write failed: " + e.getMessage());
            }
        }
    }

    private void handleHostLine(String line) {
        org.json.JSONObject message;
        try {
            message = new org.json.JSONObject(line);
        } catch (Exception e) {
            Log.w(TAG, "bad JSON from host: " + e.getMessage());
            return;
        }
        String kind = message.optString("type");
        if ("workspace_state".equals(kind)) {
            final int workspace = message.optInt("id", -1);
            Log.i(TAG, "workspace state from host: " + workspace);
            mWatchSender.execute(new Runnable() {
                @Override
                public void run() {
                    sendWorkspaceState(workspace);
                }
            });
            return;
        }
        if (!"catalog".equals(kind)) {
            Log.w(TAG, "unexpected host message: " + line);
            return;
        }
        int version = message.optInt("version", -1);
        if (version == mCatalogVersion) {
            return;
        }
        mCatalogVersion = version;
        Log.i(TAG, "catalog v" + version + " received from host");
        final byte[] payload = line.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        mWatchSender.execute(new Runnable() {
            @Override
            public void run() {
                sendCatalogToWatch(payload, mCatalogVersion);
            }
        });
    }

    private List<Node> getNodes() {
        try {
            final List<Node>[] nodesHolder = new List[1];
            final CountDownLatch nodeLatch = new CountDownLatch(1);
            Wearable.getNodeClient(this).getConnectedNodes()
                    .addOnCompleteListener(new OnCompleteListener<List<Node>>() {
                        @Override
                        public void onComplete(Task<List<Node>> task) {
                            if (task.isSuccessful()) {
                                nodesHolder[0] = task.getResult();
                            } else {
                                Log.e(TAG, "getConnectedNodes failed: "
                                        + task.getException());
                            }
                            nodeLatch.countDown();
                        }
                    });
            if (!nodeLatch.await(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "timed out waiting for connected nodes");
                return null;
            }
            return nodesHolder[0];
        } catch (Exception e) {
            Log.e(TAG, "getNodes failed: " + e.getMessage());
            return null;
        }
    }

    private void sendCatalogToWatch(byte[] payload, int version) {
        try {
            List<Node> nodes = getNodes();
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "no connected watch nodes");
                return;
            }

            int total = (payload.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
            MessageClient messages = Wearable.getMessageClient(this);
            for (Node node : nodes) {
                for (int index = 0; index < total; index++) {
                    int start = index * CHUNK_SIZE;
                    int length = Math.min(CHUNK_SIZE, payload.length - start);
                    ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
                    header.putInt(version).putShort((short) index).putShort((short) total);
                    byte[] body = new byte[8 + length];
                    System.arraycopy(header.array(), 0, body, 0, 8);
                    System.arraycopy(payload, start, body, 8, length);
                    final CountDownLatch sendLatch = new CountDownLatch(1);
                    messages.sendMessage(node.getId(), CATALOG_CHUNK_PATH, body)
                            .addOnCompleteListener(new OnCompleteListener<Integer>() {
                                @Override
                                public void onComplete(Task<Integer> task) {
                                    if (!task.isSuccessful()) {
                                        Log.e(TAG, "catalog chunk send failed: "
                                                + task.getException());
                                    }
                                    sendLatch.countDown();
                                }
                            });
                    if (!sendLatch.await(10, TimeUnit.SECONDS)) {
                        Log.w(TAG, "timed out sending catalog chunk " + index);
                        break;
                    }
                }
                Log.i(TAG, "sent catalog v" + version + " to " + node.getDisplayName()
                        + " in " + total + " chunks");
            }
        } catch (Exception e) {
            Log.e(TAG, "catalog delivery to watch failed: " + e.getMessage());
        }
    }

    private void sendWorkspaceState(int workspace) {
        if (workspace < 1 || workspace > 5) {
            return;
        }
        List<Node> nodes = getNodes();
        if (nodes == null) {
            return;
        }
        byte[] payload = new byte[] {(byte) workspace};
        for (Node node : nodes) {
            Wearable.getMessageClient(this)
                    .sendMessage(node.getId(), WORKSPACE_STATE_PATH, payload)
                    .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                        @Override
                        public void onFailure(Exception e) {
                            Log.e(TAG, "workspace state send failed: " + e.getMessage());
                        }
                    });
        }
        Log.i(TAG, "sent workspace " + workspace + " state to watch");
    }

    /** Called by PhoneListenerService when the watch requests a fresh catalog. */
    static void requestCatalog() {
        PhoneBridgeService service = sInstance;
        if (service == null) {
            Log.w(TAG, "bridge service not running; ignoring catalog request");
            return;
        }
        service.mCatalogVersion = -1;
        service.sendLine("{\"type\":\"catalog_request\"}");
    }

    /** Called by PhoneListenerService when the watch taps an app. */
    static void sendLaunch(String desktopId) {
        PhoneBridgeService service = sInstance;
        if (service == null) {
            Log.w(TAG, "bridge service not running; ignoring launch");
            return;
        }
        service.sendLine("{\"type\":\"launch\",\"id\":\"" + jsonEscape(desktopId) + "\"}");
    }

    /** Called by PhoneListenerService when the watch side zone is tapped. */
    static void sendWorkspace(int workspace) {
        PhoneBridgeService service = sInstance;
        if (service == null) {
            Log.w(TAG, "bridge service not running; ignoring workspace switch");
            return;
        }
        service.sendLine("{\"type\":\"workspace\",\"id\":" + workspace + "}");
    }

    /** Called by PhoneListenerService when the watch cycles window focus. */
    static void sendFocus() {
        PhoneBridgeService service = sInstance;
        if (service == null) {
            Log.w(TAG, "bridge service not running; ignoring focus cycle");
            return;
        }
        service.sendLine("{\"type\":\"focus\"}");
    }

    /** Called by PhoneListenerService when the watch closes the focused app. */
    static void sendClose() {
        PhoneBridgeService service = sInstance;
        if (service == null) {
            Log.w(TAG, "bridge service not running; ignoring close");
            return;
        }
        service.sendLine("{\"type\":\"close\"}");
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
