package ru.florestdev.messenger.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessaging;

import ru.florestdev.messenger.network.ApiClient;

/**
 * After login: get FCM token from Firebase, POST /api/fcm/register with session cookie.
 * Retries once after 3s if first attempt fails.
 */
public final class FcmHelper {
    private static final String TAG = "FcmHelper";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FcmHelper() {}

    public static void registerIfLoggedIn(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        // slight delay so session cookie from login is fully in CookieJar
        MAIN.postDelayed(() -> doRegister(app, true), 800);
    }

    private static void doRegister(Context app, boolean allowRetry) {
        Prefs prefs = new Prefs(app);
        if (!prefs.isLoggedIn()) {
            Log.i(TAG, "skip: not logged in");
            return;
        }
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        String token = null;
                        if (task.isSuccessful() && task.getResult() != null) {
                            token = task.getResult();
                        } else {
                            Exception e = task.getException();
                            Log.w(TAG, "getToken failed: " + (e != null ? e.getMessage() : "null")
                                    + " — check google-services.json + plugin");
                            token = prefs.getFcmToken();
                        }
                        if (token == null || token.isEmpty()) {
                            Log.e(TAG, "no FCM token available");
                            if (allowRetry) {
                                MAIN.postDelayed(() -> doRegister(app, false), 3000);
                            }
                            return;
                        }
                        prefs.setFcmToken(token);
                        Log.i(TAG, "pushing token len=" + token.length());
                        final String tok = token;
                        ApiClient api = ApiClient.get();
                        if (api == null) {
                            Log.e(TAG, "ApiClient null");
                            return;
                        }
                        api.registerFcmToken(tok, new ApiClient.Callback<String>() {
                            @Override
                            public void onSuccess(String result) {
                                Log.i(TAG, "registered OK: " + result);
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "register FAIL: " + error);
                                if (allowRetry) {
                                    MAIN.postDelayed(() -> doRegister(app, false), 3000);
                                }
                            }
                        });
                    });
        } catch (Throwable t) {
            Log.e(TAG, "Firebase missing/broken: " + t.getMessage());
            // google-services not applied or dependency missing
        }
    }
}
