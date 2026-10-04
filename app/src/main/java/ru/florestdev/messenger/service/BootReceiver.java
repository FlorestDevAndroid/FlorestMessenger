package ru.florestdev.messenger.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ru.florestdev.messenger.util.Prefs;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (prefs.isLoggedIn() && prefs.getUsername() != null) {
            MessengerSocketService.start(context);
        }
    }
}
