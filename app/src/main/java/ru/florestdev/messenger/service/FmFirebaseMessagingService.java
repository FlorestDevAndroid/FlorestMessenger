package ru.florestdev.messenger.service;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.util.NotificationHelper;
import ru.florestdev.messenger.util.Prefs;

/**
 * FCM: used when device has no live Socket.IO session.
 * If socket is up, MessengerSocketService already shows local notifications.
 */
public class FmFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "FmFCM";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "new token");
        Prefs prefs = new Prefs(this);
        prefs.setFcmToken(token);
        if (prefs.isLoggedIn()) {
            ApiClient.get().registerFcmToken(token, new ApiClient.Callback<String>() {
                @Override public void onSuccess(String result) { Log.i(TAG, "token registered"); }
                @Override public void onError(String error) { Log.w(TAG, "register: " + error); }
            });
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);
        // If socket connected and app process alive, prefer socket path (avoid double notify)
        try {
            if (ru.florestdev.messenger.network.SocketManager.get() != null
                    && ru.florestdev.messenger.network.SocketManager.get().isConnected()) {
                // Still show if app in background? User asked: socket connected → socket push.
                // Socket service handles that; skip FCM UI when connected.
                Log.i(TAG, "socket online — skip FCM UI");
                return;
            }
        } catch (Exception ignored) {}

        Map<String, String> data = message.getData();
        String title = null;
        String body = null;
        if (message.getNotification() != null) {
            title = message.getNotification().getTitle();
            body = message.getNotification().getBody();
        }
        if (title == null || title.isEmpty()) title = data.get("from");
        if (title == null || title.isEmpty()) title = "FlorestMessenger";
        if (body == null || body.isEmpty()) body = data.get("preview");
        if (body == null) body = data.get("body");
        if (body == null) body = "Новое сообщение";

        String type = data.get("type");
        Prefs prefs = new Prefs(this);
        String active = prefs.getActiveChat();

        if ("dm".equals(type)) {
            String from = data.get("from");
            if (from != null && Prefs.dmKey(from).equals(active)) {
                return; // already viewing this DM
            }
            NotificationHelper.notifyDm(this, from != null ? from : title, body, from);
        } else if ("group".equals(type)) {
            int gid = 0;
            try { gid = Integer.parseInt(data.get("group_id")); } catch (Exception ignored) {}
            String key = Prefs.groupKey(gid);
            if (key != null && key.equals(active)) return;
            NotificationHelper.notifyGroup(this, gid, "Группа #" + gid, data.get("from"), body);
        } else {
            NotificationHelper.notifyDm(this, title, body, null);
        }
    }
}
