package ru.florestdev.messenger.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.ui.DmChatActivity;
import ru.florestdev.messenger.ui.GroupChatActivity;
import ru.florestdev.messenger.ui.MainActivity;

public class NotificationHelper {
    public static final String CHANNEL_SERVICE = "florest_service";
    public static final String CHANNEL_MESSAGES = "florest_messages";
    public static final String CHANNEL_CALLS = "florest_calls";
    public static final int ID_INCOMING_CALL = 3001;
    public static final int ID_FOREGROUND = 1001;
    private static final int BASE_MSG_ID = 2000;

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel service = new NotificationChannel(
                CHANNEL_SERVICE,
                "Фоновый сервис",
                NotificationManager.IMPORTANCE_LOW
        );
        service.setDescription("Поддержание соединения с сервером");
        service.setShowBadge(false);

        NotificationChannel messages = new NotificationChannel(
                CHANNEL_MESSAGES,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
        );
        messages.setDescription("Личные и групповые сообщения");
        messages.enableVibration(true);

        nm.createNotificationChannel(service);
        NotificationChannel calls = new NotificationChannel(
                CHANNEL_CALLS,
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
        );
        calls.setDescription("Входящие звонки");
        calls.enableVibration(true);
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(calls);

        nm.createNotificationChannel(messages);
    }

    public static Notification buildForeground(Context ctx) {
        ensureChannels(ctx);
        Intent i = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
                .setContentTitle("FlorestMessenger")
                .setContentText("Слушаю новые сообщения…")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public static void notifyDm(Context ctx, String from, String text, String partner) {
        ensureChannels(ctx);
        Prefs prefs = new Prefs(ctx);
        if (Prefs.dmKey(partner).equals(prefs.getActiveChat())) return;
        if (from != null && from.equals(prefs.getUsername())) return;

        Intent i = new Intent(ctx, DmChatActivity.class);
        i.putExtra("partner", partner);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, partner != null ? partner.hashCode() : 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String body = text != null ? text : "Новое сообщение";
        if (body.length() > 120) body = body.substring(0, 117) + "…";

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setContentTitle("ЛС от " + (from != null ? from : "кто-то"))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build();

        int id = BASE_MSG_ID + Math.abs((partner != null ? partner : "dm").hashCode() % 10000);
        try {
            NotificationManagerCompat.from(ctx).notify(id, n);
        } catch (SecurityException ignored) {}
    }

    public static void notifyGroup(Context ctx, int groupId, String groupName, String from, String text) {
        ensureChannels(ctx);
        Prefs prefs = new Prefs(ctx);
        if (Prefs.groupKey(groupId).equals(prefs.getActiveChat())) return;
        if (from != null && from.equals(prefs.getUsername())) return;
        if (!prefs.getMemberGroupIds().contains(groupId)) return;

        Intent i = new Intent(ctx, GroupChatActivity.class);
        i.putExtra("group_id", groupId);
        i.putExtra("group_name", groupName != null ? groupName : ("Группа #" + groupId));
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, groupId + 50_000, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String body = (from != null ? from + ": " : "") + (text != null ? text : "сообщение");
        if (body.length() > 120) body = body.substring(0, 117) + "…";

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setContentTitle(groupName != null ? groupName : ("Группа #" + groupId))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build();

        int id = BASE_MSG_ID + 20_000 + (groupId % 10000);
        try {
            NotificationManagerCompat.from(ctx).notify(id, n);
        } catch (SecurityException ignored) {}
    }

    public static void showIncomingCall(Context ctx, String from, String fromSid) {
        ensureChannels(ctx);
        Intent full = new Intent(ctx, ru.florestdev.messenger.ui.CallActivity.class);
        full.putExtra(ru.florestdev.messenger.ui.CallActivity.EXTRA_PARTNER, from);
        full.putExtra(ru.florestdev.messenger.ui.CallActivity.EXTRA_MODE, ru.florestdev.messenger.ui.CallActivity.MODE_INCOMING);
        full.putExtra(ru.florestdev.messenger.ui.CallActivity.EXTRA_FROM_SID, fromSid);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(
                ctx, 9101, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent accept = new Intent(ctx, ru.florestdev.messenger.ui.CallActivity.class);
        accept.putExtra(ru.florestdev.messenger.ui.CallActivity.EXTRA_PARTNER, from);
        accept.putExtra(ru.florestdev.messenger.ui.CallActivity.EXTRA_MODE, ru.florestdev.messenger.ui.CallActivity.MODE_INCOMING);
        accept.putExtra(ru.florestdev.messenger.ui.CallActivity.EXTRA_FROM_SID, fromSid);
        accept.putExtra("auto_accept", true);
        accept.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent acceptPi = PendingIntent.getActivity(
                ctx, 9102, accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_CALLS)
                .setContentTitle("Входящий звонок")
                .setContentText(from != null ? from : "Звонок")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(android.R.drawable.ic_menu_call, "Принять", acceptPi)
                .setTimeoutAfter(60_000)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(ID_INCOMING_CALL, n);
        } catch (SecurityException ignored) {}
    }

    public static void cancelIncomingCall(Context ctx) {
        try {
            NotificationManagerCompat.from(ctx).cancel(ID_INCOMING_CALL);
        } catch (Exception ignored) {}
    }

}
