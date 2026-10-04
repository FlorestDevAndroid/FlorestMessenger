package ru.florestdev.messenger.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * CookieJar with disk persistence (SharedPreferences under app private storage).
 * Survives process kill / swipe-away so Flask session stays valid.
 */
public class PersistentCookieJar implements CookieJar {
    private static final String TAG = "PersistentCookieJar";
    private static final String PREFS = "florest_cookies";
    private static final String KEY = "cookies_v1";

    private final SharedPreferences sp;
    /** host -> cookies */
    private final ConcurrentHashMap<String, List<Cookie>> store = new ConcurrentHashMap<>();

    public PersistentCookieJar(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        if (url == null || cookies == null || cookies.isEmpty()) return;
        String host = url.host();
        List<Cookie> existing = store.get(host);
        if (existing == null) {
            existing = new ArrayList<>();
            store.put(host, existing);
        }
        for (Cookie incoming : cookies) {
            for (Iterator<Cookie> it = existing.iterator(); it.hasNext(); ) {
                Cookie c = it.next();
                if (cookieEquals(c, incoming)) {
                    it.remove();
                    break;
                }
            }
            // Session-cookies (no Max-Age): OkHttp sets expiresAt=Long.MAX_VALUE — keep them
            if (!isExpired(incoming)) {
                existing.add(incoming);
            }
        }
        persist();
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> out = new ArrayList<>();
        if (url == null) return out;
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (List<Cookie> list : store.values()) {
            for (Iterator<Cookie> it = list.iterator(); it.hasNext(); ) {
                Cookie c = it.next();
                if (isExpired(c)) {
                    it.remove();
                    dirty = true;
                    continue;
                }
                if (c.matches(url)) {
                    out.add(c);
                }
            }
        }
        if (dirty) persist();
        return out;
    }

    public synchronized void clear() {
        store.clear();
        sp.edit().remove(KEY).apply();
        Log.d(TAG, "cookies cleared");
    }

    /** Force re-read from disk (rarely needed). */
    public synchronized void reload() {
        store.clear();
        load();
    }

    private static boolean cookieEquals(Cookie a, Cookie b) {
        return a.name().equals(b.name())
                && a.domain().equals(b.domain())
                && a.path().equals(b.path());
    }

    private static boolean isExpired(Cookie c) {
        // Non-persistent session cookies: expiresAt is Long.MAX_VALUE in OkHttp — treat as valid
        long exp = c.expiresAt();
        if (exp == Long.MAX_VALUE) return false;
        return exp <= System.currentTimeMillis();
    }

    private void load() {
        String raw = sp.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Cookie c = fromJson(o);
                if (c == null || isExpired(c)) continue;
                String host = o.optString("host", c.domain());
                List<Cookie> list = store.get(host);
                if (list == null) {
                    list = new ArrayList<>();
                    store.put(host, list);
                }
                list.add(c);
            }
            Log.d(TAG, "loaded cookies hosts=" + store.size());
        } catch (Exception e) {
            Log.e(TAG, "load failed", e);
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (String host : store.keySet()) {
                List<Cookie> list = store.get(host);
                if (list == null) continue;
                for (Cookie c : list) {
                    if (isExpired(c)) continue;
                    JSONObject o = toJson(c, host);
                    arr.put(o);
                }
            }
            sp.edit().putString(KEY, arr.toString()).apply();
            Log.d(TAG, "persisted " + arr.length() + " cookies");
        } catch (Exception e) {
            Log.e(TAG, "persist failed", e);
        }
    }

    private static JSONObject toJson(Cookie c, String host) throws Exception {
        JSONObject o = new JSONObject();
        o.put("host", host);
        o.put("name", c.name());
        o.put("value", c.value());
        o.put("expiresAt", c.expiresAt());
        o.put("domain", c.domain());
        o.put("path", c.path());
        o.put("secure", c.secure());
        o.put("httpOnly", c.httpOnly());
        o.put("hostOnly", c.hostOnly());
        o.put("persistent", c.persistent());
        return o;
    }

    private static Cookie fromJson(JSONObject o) {
        try {
            String name = o.getString("name");
            String value = o.getString("value");
            String domain = o.getString("domain");
            String path = o.optString("path", "/");
            long expiresAt = o.optLong("expiresAt", Long.MAX_VALUE);
            boolean secure = o.optBoolean("secure", false);
            boolean httpOnly = o.optBoolean("httpOnly", false);
            boolean hostOnly = o.optBoolean("hostOnly", false);

            Cookie.Builder b = new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path(path)
                    .expiresAt(expiresAt);
            if (hostOnly) {
                b.hostOnlyDomain(domain);
            } else {
                b.domain(domain);
            }
            if (secure) b.secure();
            if (httpOnly) b.httpOnly();
            return b.build();
        } catch (Exception e) {
            Log.e(TAG, "fromJson", e);
            return null;
        }
    }
}
