package ru.florestdev.messenger.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Set;

import ru.florestdev.messenger.model.Message;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.util.NotificationHelper;
import ru.florestdev.messenger.util.Prefs;

/**
 * Foreground-сервис: держит Socket.IO и шлёт локальные уведомления
 * по ЛС и группам, в которых состоит пользователь.
 */
public class MessengerSocketService extends Service implements SocketManager.Listener {
    private static final String TAG = "MessengerSocketService";
    public static final String ACTION_START = "ru.florestdev.messenger.action.START";
    public static final String ACTION_STOP = "ru.florestdev.messenger.action.STOP";
    public static final String ACTION_REFRESH_GROUPS = "ru.florestdev.messenger.action.REFRESH_GROUPS";

    private Prefs prefs;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, MessengerSocketService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, MessengerSocketService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    public static void refreshGroups(Context ctx) {
        Intent i = new Intent(ctx, MessengerSocketService.class);
        i.setAction(ACTION_REFRESH_GROUPS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        NotificationHelper.ensureChannels(this);
        Log.d(TAG, "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            SocketManager.get().removeListener(this);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!prefs.isLoggedIn() || prefs.getUsername() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NotificationHelper.ID_FOREGROUND, NotificationHelper.buildForeground(this));

        SocketManager sm = SocketManager.get();
        sm.addListener(this);
        sm.connect();
        joinMemberGroups();

        return START_STICKY;
    }

    private void joinMemberGroups() {
        String user = prefs.getUsername();
        if (user == null) return;
        Set<Integer> ids = prefs.getMemberGroupIds();
        SocketManager sm = SocketManager.get();
        for (Integer id : ids) {
            if (id != null) {
                sm.joinGroup(id, user);
                Log.d(TAG, "join_group " + id);
            }
        }
    }

    @Override
    public void onDestroy() {
        SocketManager.get().removeListener(this);
        Log.d(TAG, "onDestroy");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConnected() {
        Log.d(TAG, "socket connected");
        joinMemberGroups();
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "socket disconnected");
    }

    @Override
    public void onGroupMessage(Message msg) {
        if (msg == null || msg.groupId == null) return;
        int gid = msg.groupId;
        if (!prefs.getMemberGroupIds().contains(gid)) return;
        String name = prefs.getGroupName(gid);
        NotificationHelper.notifyGroup(
                this,
                gid,
                name,
                msg.username,
                msg.displayText()
        );
    }

    @Override
    public void onPrivateMessage(Message msg) {
        if (msg == null) return;
        String me = prefs.getUsername();
        String from = msg.username;
        String partner;
        if (me != null && me.equals(from)) {
            partner = msg.recipient;
        } else {
            partner = from;
        }
        if (partner == null || partner.isEmpty()) return;
        // уведомление только о входящих
        if (me != null && me.equals(from)) return;
        NotificationHelper.notifyDm(this, from, msg.displayText(), partner);
    }

    @Override
    public void onSystem(String text) {
        // no-op
    }

    @Override
    public void onIncomingCall(String from, String fromSid, String type) {
        // Avoid double-start of CallActivity (destroy→voiceLeave→"завершил звонок" on web).
        // Full-screen notification + singleTop activity is enough.
        if (ru.florestdev.messenger.ui.CallActivity.isInCallWith(from)) {
            return;
        }
        NotificationHelper.showIncomingCall(this, from, fromSid);
        try {
            // Bring UI if app in foreground; locked screen uses fullScreenIntent from notification
            ru.florestdev.messenger.ui.CallActivity.startIncoming(this, from, fromSid);
        } catch (Exception e) {
            android.util.Log.e(TAG, "start incoming call", e);
        }
    }

    @Override
    public void onCallCancelled(String from, String type) {
        NotificationHelper.cancelIncomingCall(this);
    }

    @Override
    public void onCallRejected(String from, String type) {
        NotificationHelper.cancelIncomingCall(this);
    }
}
