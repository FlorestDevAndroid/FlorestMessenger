package ru.florestdev.messenger;

import com.google.firebase.messaging.FirebaseMessaging;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.util.Prefs;

import android.app.Application;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.util.ThemeHelper;

public class MessengerApp extends Application {
    private static MessengerApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ThemeHelper.applyFromPrefs(this);
        ApiClient.init(this);
        ru.florestdev.messenger.util.FcmHelper.registerIfLoggedIn(this);
        SocketManager.init(this);
    }

    public static MessengerApp get() {
        return instance;
    }
}
