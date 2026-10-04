package ru.florestdev.messenger.network;

import android.content.Context;
import android.util.Log;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.logging.HttpLoggingInterceptor;
import ru.florestdev.messenger.model.Contact;
import ru.florestdev.messenger.model.BlogPost;
import ru.florestdev.messenger.model.AccountInfo;
import ru.florestdev.messenger.model.Organization;
import ru.florestdev.messenger.model.Group;
import ru.florestdev.messenger.model.Message;
import ru.florestdev.messenger.util.Prefs;

public class ApiClient {
    public static final String BASE = "https://messenger.florestone4185.ru";
    private static ApiClient instance;
    private final OkHttpClient client;
    private final PersistentCookieJar cookieJar;
    private final Prefs prefs;

    public static void init(Context ctx) {
        instance = new ApiClient(ctx);
    }

    public static ApiClient get() {
        return instance;
    }

    private ApiClient(Context ctx) {
        prefs = new Prefs(ctx);
        // Куки на диске (SharedPreferences) — сессия не слетает после свайпа приложения
        cookieJar = new PersistentCookieJar(ctx);

        HttpLoggingInterceptor log = new HttpLoggingInterceptor(m -> Log.d("ApiClient", m));
        log.setLevel(HttpLoggingInterceptor.Level.BASIC);

        client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(log)
                .build();
    }

    public OkHttpClient getHttpClient() {
        return client;
    }

    public void clearCookies() {
        cookieJar.clear();
    }

    public String getUsername() {
        return prefs.getUsername();
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    /** Специальный текст ошибки: нужен ввод 2FA-кода из письма */
    public static final String NEED_2FA = "NEED_2FA";

    public void login(String username, String password, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                // Сервер: @app.route('/', methods=['GET','POST']) def login()
                // Успех без 2FA -> redirect /home
                // С подтверждённой почтой -> redirect /verify_2fa
                Request getLogin = new Request.Builder()
                        .url(BASE + "/")
                        .get()
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response r = client.newCall(getLogin).execute()) {
                    if (r.body() != null) r.body().close();
                }

                RequestBody body = new FormBody.Builder()
                        .add("username", username)
                        .add("password", password)
                        .build();

                // Не следуем редиректам сами — смотрим Location
                OkHttpClient noRedirect = client.newBuilder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build();

                Request req = new Request.Builder()
                        .url(BASE + "/")
                        .post(body)
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Referer", BASE + "/")
                        .build();

                try (Response resp = noRedirect.newCall(req).execute()) {
                    int code = resp.code();
                    String loc = resp.header("Location");
                    String bodyStr = resp.body() != null ? resp.body().string() : "";
                    Log.d("ApiClient", "login code=" + code + " loc=" + loc);

                    if (loc != null && loc.contains("verify_2fa")) {
                        // Аккаунт с 2FA (привязанная почта)
                        cb.onError(NEED_2FA);
                        return;
                    }

                    boolean redirectedHome = loc != null && (
                            loc.contains("/home") || loc.contains("/groups")
                                    || loc.contains("/chat") || loc.endsWith("/") == false && loc.contains("home"));

                    boolean ok = (code >= 300 && code < 400 && redirectedHome);

                    if (!ok && code == 200) {
                        String low = bodyStr.toLowerCase();
                        if (low.contains("неверное имя") || low.contains("неверный") || low.contains("заполните все")) {
                            cb.onError("Неверное имя пользователя или пароль");
                            return;
                        }
                    }

                    // Финальная проверка сессии: GET /home с cookies
                    if (!ok) {
                        Request home = new Request.Builder()
                                .url(BASE + "/home")
                                .get()
                                .header("User-Agent", "FlorestMessenger-Android/1.0")
                                .build();
                        try (Response hr = client.newCall(home).execute()) {
                            String hb = hr.body() != null ? hr.body().string() : "";
                            String finalUrl = hr.request().url().toString();
                            boolean looksLoggedIn = hb.contains("Группы")
                                    || hb.contains("Личные")
                                    || hb.contains("account_settings")
                                    || hb.contains("/logout")
                                    || (username != null && hb.contains(username));
                            boolean looksLoginPage = hb.contains("name=\"password\"")
                                    || hb.contains("type=\"password\"")
                                    || finalUrl.endsWith("/login")
                                    || (finalUrl.equals(BASE + "/") || finalUrl.equals(BASE + ""));
                            ok = hr.isSuccessful() && looksLoggedIn && !looksLoginPage;
                            Log.d("ApiClient", "home check ok=" + ok + " url=" + finalUrl
                                    + " loggedInHints=" + looksLoggedIn);
                        }
                    } else {
                        // follow redirect once to set session path cookies if needed
                        if (loc != null) {
                            String followUrl = loc.startsWith("http") ? loc : (BASE + (loc.startsWith("/") ? loc : "/" + loc));
                            Request follow = new Request.Builder().url(followUrl).get()
                                    .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                            try (Response fr = client.newCall(follow).execute()) {
                                if (fr.body() != null) fr.body().close();
                            }
                        }
                    }

                    if (ok) {
                        prefs.setUsername(username);
                        prefs.setLoggedIn(true);
                        cb.onSuccess(true);
                    } else {
                        cb.onError("Неверное имя пользователя или пароль");
                    }
                }
            } catch (Exception e) {
                Log.e("ApiClient", "login", e);
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка сети");
            }
        }).start();
    }


    public void verify2fa(String code, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                OkHttpClient noRedirect = client.newBuilder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build();
                RequestBody body = new FormBody.Builder()
                        .add("code", code.trim())
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/verify_2fa")
                        .post(body)
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .header("Referer", BASE + "/verify_2fa")
                        .build();
                try (Response resp = noRedirect.newCall(req).execute()) {
                    int codeHttp = resp.code();
                    String loc = resp.header("Location");
                    String bodyStr = resp.body() != null ? resp.body().string() : "";
                    Log.d("ApiClient", "2fa code=" + codeHttp + " loc=" + loc);

                    boolean ok = (codeHttp >= 300 && codeHttp < 400 && loc != null && loc.contains("home"));
                    if (!ok) {
                        Request home = new Request.Builder().url(BASE + "/home").get()
                                .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                        try (Response hr = client.newCall(home).execute()) {
                            String hb = hr.body() != null ? hr.body().string() : "";
                            String finalUrl = hr.request().url().toString();
                            ok = hr.isSuccessful()
                                    && (hb.contains("Группы") || hb.contains("Личные")
                                    || hb.contains("account_settings") || hb.contains("Привет"));
                            if (finalUrl.contains("verify_2fa") || bodyStr.contains("Неверный код")
                                    || bodyStr.contains("истёк") || bodyStr.contains("Введите код")) {
                                ok = false;
                            }
                        }
                    } else if (loc != null) {
                        String followUrl = loc.startsWith("http") ? loc : (BASE + (loc.startsWith("/") ? loc : "/" + loc));
                        Request follow = new Request.Builder().url(followUrl).get()
                                .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                        try (Response fr = client.newCall(follow).execute()) {
                            if (fr.body() != null) fr.body().close();
                        }
                    }

                    if (ok) {
                        // username already in pending; get from prefs if we stored, else from session page
                        String u = prefs.getUsername();
                        if (u == null || u.isEmpty()) {
                            // leave as-is; LoginActivity should set before calling
                        }
                        prefs.setLoggedIn(true);
                        cb.onSuccess(true);
                    } else {
                        String err = "Неверный или просроченный код 2FA";
                        if (bodyStr.contains("истёк")) err = "Код истёк, войдите снова";
                        if (bodyStr.contains("Неверный")) err = "Неверный код 2FA";
                        cb.onError(err);
                    }
                }
            } catch (Exception e) {
                Log.e("ApiClient", "verify2fa", e);
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка сети");
            }
        }).start();
    }

    public void register(String username, String password, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("username", username)
                        .add("password", password)
                        .add("accept_tos", "1")
                        .add("accept_privacy", "1")
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/register")
                        .post(body)
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    int code = resp.code();
                    String bodyStr = resp.body() != null ? resp.body().string() : "";
                    String loc = resp.header("Location");
                    boolean ok = (code >= 300 && code < 400) ||
                            (code == 200 && !bodyStr.toLowerCase().contains("занят") && !bodyStr.toLowerCase().contains("error"));
                    if (ok || (loc != null && (loc.contains("login") || loc.contains("home")))) {
                        // after register often redirect to login — auto login
                        login(username, password, cb);
                    } else {
                        cb.onError("Не удалось зарегистрироваться (ник занят?)");
                    }
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка сети");
            }
        }).start();
    }

    public void logout(Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/logout").get().build();
                client.newCall(req).execute().close();
            } catch (Exception ignored) {}
            prefs.clear();
            cookieJar.clear();
            if (cb != null) cb.onSuccess(true);
        }).start();
    }

    public void fetchGroups(Callback<List<Group>> cb) {
        fetchGroups(null, cb);
    }

    /** @param query поиск по названию/создателю среди ВСЕХ групп (сервер: GET /groups?q=) */
    public void fetchGroups(String query, Callback<List<Group>> cb) {
        new Thread(() -> {
            try {
                String url = BASE + "/groups";
                if (query != null && !query.trim().isEmpty()) {
                    url += "?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8");
                }
                Request req = new Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        cb.onError("HTTP " + resp.code());
                        return;
                    }
                    String html = resp.body() != null ? resp.body().string() : "";
                    List<Group> groups = parseGroupsHtml(html);
                    cb.onSuccess(groups);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    private List<Group> parseGroupsHtml(String html) {
        List<Group> list = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements cards = doc.select(".gcard");
            for (Element card : cards) {
                Group g = new Group();
                String name = card.attr("data-name");
                String creator = card.attr("data-creator");
                Element nameEl = card.selectFirst(".gname");
                if (nameEl != null) {
                    g.name = nameEl.ownText().trim();
                    if (g.name.isEmpty()) g.name = nameEl.text().replace("ты внутри", "").trim();
                } else {
                    g.name = name;
                }
                Element meta = card.selectFirst(".gmeta");
                if (meta != null) {
                    String t = meta.text();
                    if (t.contains("создал")) g.creator = t.replace("создал", "").trim();
                }
                if (g.creator == null || g.creator.isEmpty()) g.creator = creator;
                Element desc = card.selectFirst(".gdesc");
                g.description = desc != null ? desc.text() : "";
                Element img = card.selectFirst("img.gava");
                if (img != null) {
                    String src = img.attr("src");
                    if (src.startsWith("/")) src = BASE + src;
                    g.avatarUrl = src;
                }
                g.isMember = card.select(".badge").size() > 0 ||
                        card.select("a.btn[href*=/group/]").size() > 0;
                // extract id from /group/ID
                Element open = card.selectFirst("a.btn[href*=/group/]");
                if (open != null) {
                    String href = open.attr("href");
                    try {
                        String idPart = href.replaceAll(".*/group/", "").replaceAll("[^0-9].*", "");
                        g.id = Integer.parseInt(idPart);
                    } catch (Exception ignored) {}
                } else {
                    Element form = card.selectFirst("form[action*=/group/]");
                    if (form != null) {
                        String action = form.attr("action");
                        try {
                            String idPart = action.replaceAll(".*/group/", "").replaceAll("[^0-9].*", "");
                            g.id = Integer.parseInt(idPart);
                        } catch (Exception ignored) {}
                    }
                }
                if (g.name != null && !g.name.isEmpty()) list.add(g);
            }
        } catch (Exception e) {
            Log.e("ApiClient", "parseGroups", e);
        }
        return list;
    }

    public void joinGroup(int groupId, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder().build();
                Request req = new Request.Builder()
                        .url(BASE + "/group/" + groupId + "/join")
                        .post(body)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    cb.onSuccess(resp.isSuccessful() || resp.code() == 302);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        }).start();
    }

    public void createGroup(String name, String desc, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("group_name", name)
                        .add("group_description", desc != null ? desc : "")
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/groups")
                        .post(body)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    cb.onSuccess(resp.isSuccessful() || resp.code() == 302);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        }).start();
    }

    public void fetchDmHistory(String partner, Callback<List<Message>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/api/dm_history?partner=" + java.net.URLEncoder.encode(partner, "UTF-8"))
                        .get()
                        .header("Accept", "application/json")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        cb.onError("HTTP " + resp.code());
                        return;
                    }
                    String json = resp.body() != null ? resp.body().string() : "{}";
                    JSONObject root = new JSONObject(json);
                    if (!root.optBoolean("ok", false)) {
                        cb.onError(root.optString("error", "error"));
                        return;
                    }
                    JSONArray arr = root.optJSONArray("messages");
                    List<Message> msgs = new ArrayList<>();
                    String me = prefs.getUsername();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            Message m = new Message();
                            m.id = o.optLong("id");
                            m.username = o.optString("sender", o.optString("username"));
                            m.content = o.optString("content");
                            m.message = o.optString("message", m.content);
                            m.mimeType = o.optString("mime_type", null);
                            if (m.mimeType != null && "null".equals(m.mimeType)) m.mimeType = null;
                            m.type = o.optString("type", "text");
                            String u = o.optString("url", "");
                            if (u.isEmpty()) u = o.optString("content", "");
                            boolean isMedia = "media".equals(m.type) || "sticker".equals(m.type)
                                    || (u != null && (u.contains("/Uploads/") || u.contains("/stickers/")))
                                    || (m.mimeType != null && !m.mimeType.isEmpty());
                            if (isMedia) {
                                m.type = "sticker".equals(m.type) ? "sticker" : "media";
                                // keep raw path, only strip accidental tags
                                if (u != null) u = u.replaceAll("<[^>]+>", "").trim();
                                if (u != null && u.startsWith("/")) u = BASE + u;
                                m.url = u;
                                m.content = u;
                                m.message = u;
                            } else {
                                // strip simple html for text
                                m.message = Jsoup.parse(m.message != null ? m.message : "").text();
                            }
                            m.avatarUrl = o.optString("avatar_url");
                            if (m.avatarUrl != null && m.avatarUrl.startsWith("/"))
                                m.avatarUrl = BASE + m.avatarUrl;
                            m.timestamp = o.optString("timestamp");
                            m.isOwn = me != null && me.equals(m.username);
                            m.isPrivate = true;
                            m.recipient = partner;

                            // inline bot buttons
                            try {
                                org.json.JSONArray btns = o.optJSONArray("buttons");
                                if (btns != null) {
                                    java.util.List<Message.MsgButton> bl = new java.util.ArrayList<>();
                                    for (int bi = 0; bi < btns.length(); bi++) {
                                        org.json.JSONObject b = btns.optJSONObject(bi);
                                        if (b == null) continue;
                                        Message.MsgButton mb = new Message.MsgButton();
                                        mb.text = b.optString("text", b.optString("name", "…"));
                                        String u2 = b.optString("url", "");
                                        mb.url = u2.isEmpty() ? null : u2;
                                        String c2 = b.optString("callback_data", "");
                                        mb.callbackData = c2.isEmpty() ? null : c2;
                                        if (mb.text != null && !mb.text.isEmpty()) bl.add(mb);
                                    }
                                    m.buttons = bl;
                                }
                            } catch (Exception ignored) {}
                            msgs.add(m);
                        }
                    }
                    cb.onSuccess(msgs);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void fetchContacts(Callback<List<Contact>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/dm").get()
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String html = resp.body() != null ? resp.body().string() : "";
                    List<Contact> list = new ArrayList<>();
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                    Document doc = Jsoup.parse(html);
                    // ТОЛЬКО реальные контакты из списка, не allPossibleUsers
                    Elements items = doc.select("#contacts .contact-item, .contact-item");
                    for (Element el : items) {
                        // пропускаем результаты поиска в search-results
                        if (el.parent() != null && "search-results".equals(el.parent().id())) continue;
                        String t = el.text();
                        String name = el.attr("data-name");
                        if (name == null || name.isEmpty()) {
                            name = t.replace("(бот)", "").replace("(онлайн)", "").trim();
                        }
                        String onclick = el.attr("onclick");
                        if (onclick.contains("selectContact")) {
                            int s = onclick.indexOf("'");
                            int e = onclick.lastIndexOf("'");
                            if (s >= 0 && e > s) name = onclick.substring(s + 1, e);
                        }
                        if (name == null || name.isEmpty()) continue;
                        String key = name.toLowerCase();
                        if (seen.contains(key)) continue;
                        seen.add(key);
                        boolean bot = t.contains("(бот)");
                        boolean online = t.contains("(онлайн)");
                        list.add(new Contact(name, online, bot));
                    }
                    cb.onSuccess(list);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * Проверка ника на сервере (есть ли пользователь/бот).
     * Парсит allPossibleUsers только для точного/частичного совпадения по запросу — список не отдаём целиком.
     */
    public void searchUsers(String query, Callback<List<Contact>> cb) {
        new Thread(() -> {
            try {
                String q = query != null ? query.trim() : "";
                if (q.isEmpty()) {
                    cb.onSuccess(new ArrayList<>());
                    return;
                }
                Request req = new Request.Builder().url(BASE + "/dm").get()
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String html = resp.body() != null ? resp.body().string() : "";
                    List<Contact> list = new ArrayList<>();
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                    String lower = q.toLowerCase();

                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("allPossibleUsers\\s*=\\s*(\\[.*?\\])", java.util.regex.Pattern.DOTALL)
                            .matcher(html);
                    if (m.find()) {
                        java.util.regex.Matcher um = java.util.regex.Pattern
                                .compile("[\\\"']([^\\\"']+)[\\\"']")
                                .matcher(m.group(1));
                        while (um.find()) {
                            String name = um.group(1).trim();
                            if (name.isEmpty()) continue;
                            if (!name.toLowerCase().contains(lower)) continue;
                            String key = name.toLowerCase();
                            if (seen.contains(key)) continue;
                            seen.add(key);
                            list.add(new Contact(name, false, false));
                            if (list.size() >= 20) break;
                        }
                    }
                    // bots
                    java.util.regex.Matcher bm = java.util.regex.Pattern
                            .compile("botsList\\s*=\\s*(\\[.*?\\])", java.util.regex.Pattern.DOTALL)
                            .matcher(html);
                    if (bm.find()) {
                        java.util.regex.Matcher um = java.util.regex.Pattern
                                .compile("[\\\"']([^\\\"']+)[\\\"']")
                                .matcher(bm.group(1));
                        while (um.find()) {
                            String name = um.group(1).trim();
                            if (!name.toLowerCase().contains(lower)) continue;
                            for (Contact c : list) {
                                if (c.username.equalsIgnoreCase(name)) { c.bot = true; break; }
                            }
                        }
                    }
                    cb.onSuccess(list);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    /** Устаревший метод — оставлен для совместимости со старым SearchActivity. Не используй. */
    @Deprecated
    public void globalSearch(String query, Callback<SearchResult> cb) {
        new Thread(() -> {
            try {
                SearchResult result = new SearchResult();
                String q = query != null ? query.trim() : "";
                if (q.isEmpty()) {
                    cb.onSuccess(result);
                    return;
                }
                final Object lock = new Object();
                final int[] done = {0};
                fetchGroups(q, new Callback<List<Group>>() {
                    @Override public void onSuccess(List<Group> r) {
                        result.groups = r != null ? r : new ArrayList<>();
                        synchronized (lock) { done[0]++; lock.notifyAll(); }
                    }
                    @Override public void onError(String e) {
                        result.groups = new ArrayList<>();
                        synchronized (lock) { done[0]++; lock.notifyAll(); }
                    }
                });
                searchUsers(q, new Callback<List<Contact>>() {
                    @Override public void onSuccess(List<Contact> r) {
                        result.users = r != null ? r : new ArrayList<>();
                        synchronized (lock) { done[0]++; lock.notifyAll(); }
                    }
                    @Override public void onError(String e) {
                        result.users = new ArrayList<>();
                        synchronized (lock) { done[0]++; lock.notifyAll(); }
                    }
                });
                synchronized (lock) {
                    long deadline = System.currentTimeMillis() + 25000;
                    while (done[0] < 2 && System.currentTimeMillis() < deadline) {
                        try { lock.wait(400); } catch (InterruptedException ignored) {}
                    }
                }
                if (result.groups == null) result.groups = new ArrayList<>();
                if (result.users == null) result.users = new ArrayList<>();
                cb.onSuccess(result);
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public static class SearchResult {
        public List<Group> groups = new ArrayList<>();
        public List<Contact> users = new ArrayList<>();
    }

    public void fetchDmStatus(String partner, Callback<long[]> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/api/dm_status?partner=" + java.net.URLEncoder.encode(partner, "UTF-8"))
                        .get()
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(body);
                    long their = j.optLong("their_last_read_id", 0);
                    long my = j.optLong("my_last_read_id", 0);
                    cb.onSuccess(new long[]{their, my});
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }


    public void recoveryLogin(String username, String code, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("username", username)
                        .add("code", code)
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/recovery_login")
                        .post(body)
                        .header("Accept", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (j.optBoolean("ok") || resp.isSuccessful()) {
                        prefs.setLoggedIn(true);
                        prefs.setUsername(username);
                        cb.onSuccess(true);
                    } else {
                        cb.onError(j.optString("error", "Неверный код"));
                    }
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void generateRecoveryCodes(Callback<java.util.List<String>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/api/recovery/generate")
                        .post(new FormBody.Builder().build())
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (!j.optBoolean("ok")) {
                        cb.onError(j.optString("error", "Ошибка"));
                        return;
                    }
                    java.util.List<String> codes = new ArrayList<>();
                    org.json.JSONArray arr = j.optJSONArray("codes");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) codes.add(arr.getString(i));
                    cb.onSuccess(codes);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void fetchOrgs(Callback<java.util.List<Organization>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/api/orgs").get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (!j.optBoolean("ok", true) && j.has("error")) {
                        cb.onError(j.optString("error"));
                        return;
                    }
                    java.util.List<Organization> list = new ArrayList<>();
                    org.json.JSONArray arr = j.optJSONArray("orgs");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            Organization org = new Organization();
                            org.id = o.optInt("id");
                            org.name = o.optString("name");
                            org.owner = o.optString("owner");
                            org.inviteSlug = o.optString("invite_slug");
                            org.description = o.optString("description");
                            org.role = o.optString("role");
                            list.add(org);
                        }
                    }
                    cb.onSuccess(list);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void createOrg(String name, String slug, String desc, Callback<Organization> cb) {
        new Thread(() -> {
            try {
                FormBody.Builder b = new FormBody.Builder().add("name", name);
                if (slug != null && !slug.isEmpty()) b.add("invite_slug", slug);
                if (desc != null) b.add("description", desc);
                Request req = new Request.Builder().url(BASE + "/api/orgs/create").post(b.build())
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (!j.optBoolean("ok")) {
                        cb.onError(j.optString("error", "Ошибка"));
                        return;
                    }
                    org.json.JSONObject o = j.getJSONObject("org");
                    Organization org = new Organization();
                    org.id = o.optInt("id");
                    org.name = o.optString("name");
                    org.inviteSlug = o.optString("invite_slug");
                    org.role = o.optString("role");
                    cb.onSuccess(org);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void joinOrg(String slug, Callback<Organization> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/api/orgs/join/" + java.net.URLEncoder.encode(slug, "UTF-8"))
                        .post(new FormBody.Builder().build())
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (!j.optBoolean("ok")) {
                        cb.onError(j.optString("error", "Ошибка"));
                        return;
                    }
                    Organization org = new Organization();
                    org.id = j.optInt("org_id");
                    org.name = j.optString("name");
                    cb.onSuccess(org);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void fetchOrg(int orgId, Callback<Organization> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/api/orgs/" + orgId).get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (!j.optBoolean("ok")) {
                        cb.onError(j.optString("error", "Ошибка"));
                        return;
                    }
                    org.json.JSONObject o = j.getJSONObject("org");
                    Organization org = new Organization();
                    org.id = o.optInt("id");
                    org.name = o.optString("name");
                    org.owner = o.optString("owner");
                    org.inviteSlug = o.optString("invite_slug");
                    org.description = o.optString("description");
                    org.role = o.optString("role");
                    org.json.JSONArray chats = j.optJSONArray("chats");
                    if (chats != null) {
                        for (int i = 0; i < chats.length(); i++) {
                            org.json.JSONObject c = chats.getJSONObject(i);
                            Organization.Chat ch = new Organization.Chat();
                            ch.id = c.optInt("id");
                            ch.name = c.optString("name");
                            org.chats.add(ch);
                        }
                    }
                    org.json.JSONArray members = j.optJSONArray("members");
                    if (members != null) {
                        for (int i = 0; i < members.length(); i++) {
                            org.json.JSONObject m = members.getJSONObject(i);
                            Organization.Member mem = new Organization.Member();
                            mem.username = m.optString("username");
                            mem.role = m.optString("role");
                            org.members.add(mem);
                        }
                    }
                    cb.onSuccess(org);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void createOrgChat(int orgId, String name, Callback<Organization.Chat> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder().add("name", name).build();
                Request req = new Request.Builder().url(BASE + "/api/orgs/" + orgId + "/chats").post(body)
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (!j.optBoolean("ok")) {
                        cb.onError(j.optString("error", "Ошибка"));
                        return;
                    }
                    org.json.JSONObject c = j.getJSONObject("chat");
                    Organization.Chat ch = new Organization.Chat();
                    ch.id = c.optInt("id");
                    ch.name = c.optString("name");
                    cb.onSuccess(ch);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }


    public void fetchOrgMessages(int orgId, int chatId, Callback<java.util.List<Message>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/api/orgs/" + orgId + "/chats/" + chatId + "/messages")
                        .get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    java.util.List<Message> list = new ArrayList<>();
                    org.json.JSONArray arr = j.optJSONArray("messages");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            Message m = new Message();
                            m.id = o.optLong("id");
                            m.username = o.optString("username");
                            m.content = o.optString("content", o.optString("message", ""));
                            m.message = m.content;
                            m.isOwn = prefs.getUsername() != null && prefs.getUsername().equals(m.username);
                            m.groupId = -chatId;
                            list.add(m);
                        }
                    }
                    cb.onSuccess(list);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void sendOrgMessage(int orgId, int chatId, String text, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("message", text)
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/api/orgs/" + orgId + "/chats/" + chatId + "/messages")
                        .post(body)
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (j.optBoolean("ok") || resp.isSuccessful()) cb.onSuccess(true);
                    else cb.onError(j.optString("error", "Не отправлено"));
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }



    public void fetchStickerPacks(Callback<java.util.List<java.util.Map<String, Object>>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/api/stickers/packs").get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    java.util.List<java.util.Map<String, Object>> list = new ArrayList<>();
                    org.json.JSONArray arr = j.optJSONArray("packs");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            java.util.Map<String, Object> m = new java.util.HashMap<>();
                            m.put("id", o.optInt("id"));
                            m.put("name", o.optString("name"));
                            m.put("invite_slug", o.optString("invite_slug"));
                            list.add(m);
                        }
                    }
                    cb.onSuccess(list);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void joinStickerPack(String slug, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                String packSlug = slug != null ? slug.trim() : "";
                if (packSlug.contains("/")) packSlug = packSlug.substring(packSlug.lastIndexOf('/') + 1);
                final String slugFinal = packSlug;
                Request req = new Request.Builder()
                        .url(BASE + "/api/stickers/packs/join/" + java.net.URLEncoder.encode(slugFinal, "UTF-8"))
                        .post(new FormBody.Builder().build())
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (j.optBoolean("ok")) cb.onSuccess(true);
                    else cb.onError(j.optString("error", "Ошибка"));
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void fetchPackStickers(int packId, Callback<java.util.List<String>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/api/stickers/packs/" + packId).get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    java.util.List<String> urls = new ArrayList<>();
                    org.json.JSONArray arr = j.optJSONArray("stickers");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String u = arr.getJSONObject(i).optString("url");
                            if (u != null && !u.isEmpty()) {
                                if (u.startsWith("/")) u = BASE + u;
                                urls.add(u);
                            }
                        }
                    }
                    cb.onSuccess(urls);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }


    /** POST account_settings action=resend_recovery — шлёт коды на почту */
    public void resendRecoveryCodes(Callback<String> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("action", "resend_recovery")
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/account_settings")
                        .post(body)
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() || resp.code() == 302 || resp.code() == 200) {
                        cb.onSuccess("Recovery-коды отправлены на почту (и показаны в веб-аккаунте если открыт)");
                    } else {
                        cb.onError("Ошибка " + resp.code());
                    }
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }


    public void fetchStories(Callback<String> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/api/stories").get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    cb.onSuccess(s);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void createPoll(String scope, int scopeId, Integer chatId, String question, java.util.List<String> options, String pollType, Integer correctIndex, Callback<String> cb) {
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("scope", scope);
                body.put("scope_id", scopeId);
                if (chatId != null) body.put("chat_id", chatId);
                body.put("question", question);
                body.put("options", new org.json.JSONArray(options));
                body.put("poll_type", pollType != null ? pollType : "poll");
                if (correctIndex != null) body.put("correct_index", correctIndex);
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder().url(BASE + "/api/polls").post(rb)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (j.optBoolean("ok")) cb.onSuccess(s);
                    else cb.onError(j.optString("error", "Ошибка"));
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void votePoll(int pollId, int optionIndex, Callback<String> cb) {
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("option_index", optionIndex);
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder().url(BASE + "/api/polls/" + pollId + "/vote").post(rb)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    cb.onSuccess(s);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }


    public void viewStory(int storyId, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/api/stories/" + storyId + "/view")
                        .post(new FormBody.Builder().build())
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    cb.onSuccess(resp.isSuccessful());
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }


    public void fetchTgStatus(Callback<String> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(BASE + "/api/tg/status").get()
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    cb.onSuccess(s);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void tgLinkChannel(String channel, Callback<String> cb) {
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("channel", channel);
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder().url(BASE + "/api/tg/link").post(rb)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(s);
                    if (j.optBoolean("ok")) cb.onSuccess(s);
                    else cb.onError(j.optString("error", "Ошибка"));
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void tgUnlinkChannel(long channelId, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("channel_id", channelId);
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder().url(BASE + "/api/tg/unlink").post(rb)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    cb.onSuccess(resp.isSuccessful());
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }


    /** Upload file like web /upload. Pass recipient for DM or groupId for group. */
    public void uploadMedia(java.io.File file, String mime, String recipient, Integer groupId, Callback<Message> cb) {
        new Thread(() -> {
            try {
                String m = mime != null ? mime : "application/octet-stream";
                RequestBody fileBody = RequestBody.create(file, MediaType.parse(m));
                MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(), fileBody);
                if (recipient != null && !recipient.isEmpty())
                    mb.addFormDataPart("recipient", recipient);
                if (groupId != null)
                    mb.addFormDataPart("group_id", String.valueOf(groupId));
                Request req = new Request.Builder()
                        .url(BASE + "/upload")
                        .post(mb.build())
                        .header("Accept", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "{}";
                    org.json.JSONObject j = new org.json.JSONObject(body);
                    if (!resp.isSuccessful() || !j.optBoolean("ok", resp.isSuccessful())) {
                        cb.onError(j.optString("error", "Ошибка загрузки (" + resp.code() + ")"));
                        return;
                    }
                    Message msg = new Message();
                    msg.id = j.optLong("id");
                    msg.type = "media";
                    msg.mimeType = j.optString("mime_type", m);
                    String url = j.optString("url", "");
                    if (url.startsWith("/")) url = BASE + url;
                    msg.url = url;
                    msg.content = url;
                    msg.message = url;
                    msg.isOwn = true;
                    msg.username = prefs.getUsername();
                    if (recipient != null) {
                        msg.isPrivate = true;
                        msg.recipient = recipient;
                    }
                    if (groupId != null) msg.groupId = groupId;
                    cb.onSuccess(msg);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка загрузки");
            }
        }).start();
    }


    public void registerFcmToken(String token, Callback<String> cb) {
        if (token == null || token.isEmpty()) {
            if (cb != null) cb.onError("empty token");
            return;
        }
        new Thread(() -> {
            try {
                // form — надёжнее с Flask session cookie
                RequestBody rb = new FormBody.Builder()
                        .add("token", token)
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/api/fcm/register")
                        .post(rb)
                        .header("Accept", "application/json")
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String s = resp.body() != null ? resp.body().string() : "{}";
                    Log.d("ApiClient", "fcm register HTTP " + resp.code() + " " + s);
                    if (!resp.isSuccessful()) {
                        if (cb != null) cb.onError("HTTP " + resp.code() + " " + s);
                        return;
                    }
                    if (s.contains("\"ok\": false") || s.contains("\"ok\":false")) {
                        if (cb != null) cb.onError(s);
                        return;
                    }
                    if (cb != null) cb.onSuccess(s);
                }
            } catch (Exception e) {
                Log.e("ApiClient", "fcm register", e);
                if (cb != null) cb.onError(e.getMessage());
            }
        }).start();
    }

    public void unregisterFcmToken(String token, Callback<String> cb) {
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                if (token != null) body.put("token", token);
                RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder()
                        .url(BASE + "/api/fcm/unregister")
                        .post(rb)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (cb != null) cb.onSuccess("ok");
                }
            } catch (Exception e) {
                if (cb != null) cb.onError(e.getMessage());
            }
        }).start();
    }

    public String avatarUrl(String username) {
        return BASE + "/avatar/" + username;
    }

    public String groupAvatarUrl(int id) {
        return BASE + "/group_avatar/" + id;
    }

    // ===================== BLOGS =====================

    public void fetchBlogs(Callback<List<BlogPost>> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/blogs")
                        .get()
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        cb.onError("HTTP " + resp.code());
                        return;
                    }
                    String html = resp.body() != null ? resp.body().string() : "";
                    cb.onSuccess(parseBlogsHtml(html));
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    private List<BlogPost> parseBlogsHtml(String html) {
        List<BlogPost> list = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements cards = doc.select("article.post, article.card.post, .card.post");
            for (Element card : cards) {
                BlogPost p = new BlogPost();
                Element idInput = card.selectFirst("input[name=post_id]");
                if (idInput != null) {
                    try { p.id = Integer.parseInt(idInput.attr("value").trim()); } catch (Exception ignored) {}
                }
                Element title = card.selectFirst(".title, .ptitle, .post-title");
                p.title = title != null ? title.text().trim() : "";
                Element meta = card.selectFirst(".meta");
                if (meta != null) {
                    Element a = meta.selectFirst("a");
                    p.author = a != null ? a.text().trim() : meta.text().trim();
                    p.createdAt = meta.text();
                }
                Element body = card.selectFirst(".body, .pcontent, .post-content");
                p.content = body != null ? body.text().trim() : "";
                Element likeBtn = card.selectFirst("button.btn.ghost, button:contains(Лайк), button:contains(♥), button:contains(♡)");
                if (likeBtn == null) likeBtn = card.selectFirst(".actions button");
                if (likeBtn != null) {
                    String lt = likeBtn.text();
                    p.liked = likeBtn.hasClass("liked") || lt.contains("♥");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(lt);
                    if (m.find()) {
                        try { p.likesCount = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                    }
                }
                for (Element ce : card.select(".comment")) {
                    BlogPost.Comment c = new BlogPost.Comment();
                    Element b = ce.selectFirst("b, strong");
                    c.username = b != null ? b.text().trim() : "";
                    Element muted = ce.selectFirst(".muted");
                    c.createdAt = muted != null ? muted.text().trim() : "";
                    // content is in inner div after b
                    Elements divs = ce.select("div > div");
                    if (!divs.isEmpty()) {
                        c.content = divs.last().text().trim();
                    } else {
                        c.content = ce.ownText().trim();
                        if (c.content.isEmpty()) c.content = ce.text().trim();
                    }
                    p.comments.add(c);
                }
                if (p.id > 0) list.add(p);
            }
        } catch (Exception e) {
            Log.e("ApiClient", "parseBlogs", e);
        }
        return list;
    }

    public void blogAction(String action, int postId, String extraField, String extraValue, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                FormBody.Builder b = new FormBody.Builder()
                        .add("action", action)
                        .add("post_id", String.valueOf(postId));
                if (extraField != null && extraValue != null) {
                    b.add(extraField, extraValue);
                }
                Request req = new Request.Builder()
                        .url(BASE + "/blogs")
                        .post(b.build())
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .header("Referer", BASE + "/blogs")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    cb.onSuccess(resp.isSuccessful() || resp.code() == 302);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void createBlogPost(String title, String content, Callback<Boolean> cb) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("action", "create_post")
                        .add("title", title)
                        .add("content", content)
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/blogs")
                        .post(body)
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .header("Referer", BASE + "/blogs")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    cb.onSuccess(resp.isSuccessful() || resp.code() == 302);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void likeBlogPost(int postId, Callback<Boolean> cb) {
        blogAction("like_post", postId, null, null, cb);
    }

    public void commentBlogPost(int postId, String comment, Callback<Boolean> cb) {
        blogAction("add_comment", postId, "comment", comment, cb);
    }

    public void deleteBlogPost(int postId, Callback<Boolean> cb) {
        blogAction("delete_post", postId, null, null, cb);
    }

    // ===================== ACCOUNT =====================

    public void fetchAccountInfo(Callback<AccountInfo> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/account_settings")
                        .get()
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        cb.onError("HTTP " + resp.code());
                        return;
                    }
                    String html = resp.body() != null ? resp.body().string() : "";
                    AccountInfo info = new AccountInfo();
                    info.username = prefs.getUsername();
                    Document doc = Jsoup.parse(html);
                    Element bio = doc.selectFirst("textarea[name=bio], #bio");
                    if (bio != null) info.bio = bio.text();
                    // email status from paragraph
                    Elements ps = doc.select("p");
                    for (Element p : ps) {
                        String t = p.text();
                        if (t.contains("почта") || t.contains("Текущая")) {
                            info.emailStatus = t;
                            break;
                        }
                    }
                    Element emailInput = doc.selectFirst("input[name=email]");
                    if (emailInput != null) info.email = emailInput.attr("value");
                    cb.onSuccess(info);
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void accountAction(FormBody body, Callback<String> cb) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(BASE + "/account_settings")
                        .post(body)
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .header("Referer", BASE + "/account_settings")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    String html = resp.body() != null ? resp.body().string() : "";
                    Document doc = Jsoup.parse(html);
                    Element ok = doc.selectFirst(".banner.ok, .success, .ok");
                    Element err = doc.selectFirst(".banner.err, .error, .err");
                    if (err != null && err.text() != null && !err.text().isEmpty()) {
                        cb.onError(err.text());
                        return;
                    }
                    if (ok != null && ok.text() != null && !ok.text().isEmpty()) {
                        cb.onSuccess(ok.text());
                        return;
                    }
                    // username change may update session
                    if (html.contains("успеш") || html.contains("обновлен") || html.contains("изменён")
                            || html.contains("изменен") || resp.isSuccessful()) {
                        cb.onSuccess("Готово");
                    } else {
                        cb.onError("Не удалось выполнить действие");
                    }
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }

    public void updateBio(String bio, Callback<String> cb) {
        accountAction(new FormBody.Builder()
                .add("action", "update_bio")
                .add("bio", bio != null ? bio : "")
                .build(), cb);
    }

    public void changePassword(String current, String neu, Callback<String> cb) {
        accountAction(new FormBody.Builder()
                .add("action", "change_password")
                .add("current_password", current)
                .add("new_password", neu)
                .build(), cb);
    }

    public void changeUsername(String newUsername, Callback<String> cb) {
        accountAction(new FormBody.Builder()
                .add("action", "change_username")
                .add("new_username", newUsername)
                .build(), result -> {
            // on success update local prefs
            if (result != null) {
                prefs.setUsername(newUsername);
            }
            cb.onSuccess(result);
        }, err -> cb.onError(err));
    }

    // overload helper for changeUsername - fix signature
    private void accountAction(FormBody body, java.util.function.Consumer<String> onOk, java.util.function.Consumer<String> onErr) {
        accountAction(body, new Callback<String>() {
            @Override public void onSuccess(String result) { onOk.accept(result); }
            @Override public void onError(String error) { onErr.accept(error); }
        });
    }

    public void bindEmail(String email, Callback<String> cb) {
        accountAction(new FormBody.Builder()
                .add("action", "bind_email")
                .add("email", email)
                .build(), cb);
    }

    public void deleteAccount(String password, Callback<String> cb) {
        accountAction(new FormBody.Builder()
                .add("action", "delete_account")
                .add("password", password)
                .build(), cb);
    }

    public void uploadAvatar(byte[] data, String filename, String mime, Callback<String> cb) {
        new Thread(() -> {
            try {
                okhttp3.MultipartBody body = new okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("action", "upload_avatar")
                        .addFormDataPart("avatar", filename != null ? filename : "avatar.jpg",
                                RequestBody.create(data, okhttp3.MediaType.parse(mime != null ? mime : "image/jpeg")))
                        .build();
                Request req = new Request.Builder()
                        .url(BASE + "/account_settings")
                        .post(body)
                        .header("User-Agent", "FlorestMessenger-Android/1.0")
                        .header("Referer", BASE + "/account_settings")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() || resp.code() == 302) {
                        cb.onSuccess("Аватар обновлён");
                    } else {
                        cb.onError("HTTP " + resp.code());
                    }
                }
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Ошибка");
            }
        }).start();
    }
}
