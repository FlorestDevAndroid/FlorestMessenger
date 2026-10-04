package ru.florestdev.messenger.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.Polling;
import io.socket.engineio.client.transports.WebSocket;
import okhttp3.OkHttpClient;
import ru.florestdev.messenger.model.Message;
import ru.florestdev.messenger.util.Prefs;

public class SocketManager {
    
    private static void parseButtonsInto(Message m, JSONObject o) {
        if (m == null || o == null) return;
        try {
            org.json.JSONArray arr = o.optJSONArray("buttons");
            if (arr == null) return;
            java.util.List<Message.MsgButton> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.get(i);
                if (!(el instanceof JSONObject)) continue;
                JSONObject b = (JSONObject) el;
                Message.MsgButton mb = new Message.MsgButton();
                mb.text = b.optString("text", b.optString("name", "…"));
                mb.url = b.optString("url", null);
                if (mb.url != null && mb.url.isEmpty()) mb.url = null;
                mb.callbackData = b.optString("callback_data", null);
                if (mb.callbackData != null && mb.callbackData.isEmpty()) mb.callbackData = null;
                if (mb.text != null && !mb.text.isEmpty()) list.add(mb);
            }
            m.buttons = list;
        } catch (Exception ignored) {}
    }

    private static final String TAG = "SocketManager";
    private static SocketManager instance;
    private Socket socket;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Prefs prefs;
    private final List<Listener> listeners = new ArrayList<>();
    private boolean connected;

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onGroupMessage(Message msg);
        void onPrivateMessage(Message msg);
        void onSystem(String text);
        default void onTyping(String username, String to) {}
        default void onStopTyping(String username, String to) {}
        default void onMessagesRead(String reader, String partner, long lastReadId) {}
        default void onReactionUpdate(long messageId, List<Message.Reaction> reactions) {}
        default void onVoicePeers(Integer groupId, List<VoicePeer> peers) {}
        default void onVoiceUserJoined(Integer groupId, String username, String sid) {}
        default void onVoiceUserLeft(Integer groupId, String username, String sid) {}
        default void onIncomingCall(String from, String fromSid, String type) {}
        default void onCallAccepted(String from, String fromSid, String type) {}
        default void onCallRejected(String from, String type) {}
        default void onCallCancelled(String from, String type) {}
        default void onCallFailed(String reason, String target) {}
        default void onVoiceOffer(String fromSid, String fromUser, JSONObject sdp, Integer groupId) {}
        default void onVoiceAnswer(String fromSid, String fromUser, JSONObject sdp, Integer groupId) {}
        default void onVoiceIce(String fromSid, String fromUser, JSONObject candidate, Integer groupId) {}
    }

    public static class VoicePeer {
        public String username;
        public String sid;
        public VoicePeer(String u, String s) { username = u; sid = s; }
    }

    public static void init(Context ctx) {
        instance = new SocketManager(ctx);
    }

    public static SocketManager get() {
        return instance;
    }

    private SocketManager(Context ctx) {
        prefs = new Prefs(ctx);
    }

    public synchronized void connect() {
        if (socket != null && socket.connected()) {
            connected = true;
            return;
        }
        try {
            // Общие cookies с ApiClient (сессия Flask)
            OkHttpClient ok = ApiClient.get().getHttpClient();
            IO.setDefaultOkHttpWebSocketFactory(ok);
            IO.setDefaultOkHttpCallFactory(ok);

            IO.Options opts = new IO.Options();
            opts.forceNew = false;
            opts.reconnection = true;
            opts.transports = new String[]{WebSocket.NAME, Polling.NAME};
            opts.callFactory = ok;
            opts.webSocketFactory = ok;

            socket = IO.socket(ApiClient.BASE, opts);
            socket.on(Socket.EVENT_CONNECT, args -> {
                connected = true;
                Log.i(TAG, "connected");
                String me = prefs.getUsername();
                if (me != null) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("username", me);
                        socket.emit("join", o);
                    } catch (Exception ignored) {}
                }
                main.post(() -> {
                    for (Listener l : copyListeners()) l.onConnected();
                });
            });
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                connected = false;
                main.post(() -> {
                    for (Listener l : copyListeners()) l.onDisconnected();
                });
            });
            socket.on("group_message", args -> handleGroup(args));
            socket.on("group_media", args -> handleGroup(args));
            socket.on("org_message", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    Message m = parseMessage(o, false);
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onGroupMessage(m);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "org_message", e);
                }
            });
            socket.on("org_chat_history", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    org.json.JSONArray arr = o.optJSONArray("messages");
                    if (arr == null) return;
                    for (int i = 0; i < arr.length(); i++) {
                        Message m = parseMessage(arr.getJSONObject(i), false);
                        main.post(() -> {
                            for (Listener l : copyListeners()) l.onGroupMessage(m);
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "org_history", e);
                }
            });
            socket.on("voice_peers", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    org.json.JSONArray peers = o.optJSONArray("peers");
                    Integer gid = o.has("group_id") && !o.isNull("group_id") ? o.optInt("group_id") : null;
                    List<VoicePeer> list = new ArrayList<>();
                    if (peers != null) {
                        for (int i = 0; i < peers.length(); i++) {
                            org.json.JSONObject p = peers.optJSONObject(i);
                            if (p != null)
                                list.add(new VoicePeer(p.optString("username", ""), p.optString("sid", "")));
                        }
                    }
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onVoicePeers(gid, list);
                    });
                } catch (Exception ignored) {}
            });
            socket.on("voice_user_joined", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    Integer gid = o.has("group_id") && !o.isNull("group_id") ? o.optInt("group_id") : null;
                    String u = o.optString("username", "");
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onVoiceUserJoined(gid, u, o.optString("sid", ""));
                    });
                } catch (Exception ignored) {}
            });
            socket.on("voice_user_left", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    Integer gid = o.has("group_id") && !o.isNull("group_id") ? o.optInt("group_id") : null;
                    String u = o.optString("username", "");
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onVoiceUserLeft(gid, u, o.optString("sid", ""));
                    });
                } catch (Exception ignored) {}
            });
            socket.on("receive_private_message", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    Message m = parseMessage(o, true);
                    // свой echo → как минимум delivered
                    String me = prefs.getUsername();
                    if (me != null && me.equals(m.username) && !"read".equals(m.status)) {
                        m.status = "delivered";
                        m.isOwn = true;
                    }
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onPrivateMessage(m);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "pm", e);
                }
            });
            socket.on("typing", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    String u = o.optString("username", "");
                    String to = o.optString("to", null);
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onTyping(u, to);
                    });
                } catch (Exception ignored) {}
            });
            socket.on("stop_typing", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    String u = o.optString("username", "");
                    String to = o.optString("to", null);
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onStopTyping(u, to);
                    });
                } catch (Exception ignored) {}
            });
            socket.on("messages_read", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    String reader = o.optString("reader", "");
                    String partner = o.optString("partner", "");
                    long last = o.optLong("last_read_id", 0);
                    Log.i(TAG, "messages_read reader=" + reader + " last=" + last);
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onMessagesRead(reader, partner, last);
                    });
                } catch (Exception ignored) {}
            });
            socket.on("reaction_update", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    long mid = o.optLong("message_id", 0);
                    List<Message.Reaction> list = parseReactions(o.optJSONArray("reactions"));
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onReactionUpdate(mid, list);
                    });
                } catch (Exception ignored) {}
            });
            
            socket.on("incoming_call", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    String from = o.optString("from");
                    String fromSid = o.optString("from_sid");
                    String type = o.optString("type", "dm");
                    main.post(() -> {
                        for (Listener l : copyListeners()) l.onIncomingCall(from, fromSid, type);
                    });
                } catch (Exception e) { Log.e(TAG, "incoming_call", e); }
            });
            socket.on("call_accepted", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    main.post(() -> {
                        for (Listener l : copyListeners())
                            l.onCallAccepted(o.optString("from"), o.optString("from_sid"), o.optString("type", "dm"));
                    });
                } catch (Exception e) { Log.e(TAG, "call_accepted", e); }
            });
            socket.on("call_rejected", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    main.post(() -> {
                        for (Listener l : copyListeners())
                            l.onCallRejected(o.optString("from"), o.optString("type", "dm"));
                    });
                } catch (Exception e) { Log.e(TAG, "call_rejected", e); }
            });
            socket.on("call_cancelled", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    main.post(() -> {
                        for (Listener l : copyListeners())
                            l.onCallCancelled(o.optString("from"), o.optString("type", "dm"));
                    });
                } catch (Exception e) { Log.e(TAG, "call_cancelled", e); }
            });
            socket.on("call_failed", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    main.post(() -> {
                        for (Listener l : copyListeners())
                            l.onCallFailed(o.optString("reason"), o.optString("target"));
                    });
                } catch (Exception e) { Log.e(TAG, "call_failed", e); }
            });
            socket.on("voice_offer", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    Integer gid = o.has("group_id") && !o.isNull("group_id") ? o.optInt("group_id") : null;
                    JSONObject sdp = extractSdp(o);
                    final JSONObject sdpF = sdp;
                    main.post(() -> {
                        for (Listener l : copyListeners())
                            l.onVoiceOffer(o.optString("from_sid"), o.optString("from_user"), sdpF, gid);
                    });
                } catch (Exception e) { Log.e(TAG, "voice_offer", e); }
            });
            socket.on("voice_answer", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    Integer gid = o.has("group_id") && !o.isNull("group_id") ? o.optInt("group_id") : null;
                    JSONObject sdp = extractSdp(o);
                    final JSONObject sdpF = sdp;
                    main.post(() -> {
                        for (Listener l : copyListeners())
                            l.onVoiceAnswer(o.optString("from_sid"), o.optString("from_user"), sdpF, gid);
                    });
                } catch (Exception e) { Log.e(TAG, "voice_answer", e); }
            });
            socket.on("voice_ice", args -> {
                try {
                    JSONObject o = toJson(args);
                    if (o == null) return;
                    Integer gid = o.has("group_id") && !o.isNull("group_id") ? o.optInt("group_id") : null;
                    JSONObject cand = o.optJSONObject("candidate");
                    if (cand == null && o.has("candidate")) {
                        // candidate may be a string or already-flat fields
                        cand = new JSONObject();
                        Object raw = o.opt("candidate");
                        if (raw instanceof String) {
                            cand.put("candidate", raw);
                            cand.put("sdpMid", o.optString("sdpMid", "0"));
                            cand.put("sdpMLineIndex", o.optInt("sdpMLineIndex", 0));
                        } else {
                            try { cand = new JSONObject(String.valueOf(raw)); } catch (Exception ignored) {}
                        }
                    }
                    final JSONObject candF = cand;
                    main.post(() -> {
                        for (Listener l : copyListeners())
                            l.onVoiceIce(o.optString("from_sid"), o.optString("from_user"), candF, gid);
                    });
                } catch (Exception e) { Log.e(TAG, "voice_ice", e); }
            });

            socket.connect();
        } catch (Exception e) {
            Log.e(TAG, "connect", e);
        }
    }

    private void handleGroup(Object[] args) {
        try {
            JSONObject o = toJson(args);
            if (o == null) return;
            if (!o.has("type") && o.has("url")) o.put("type", "media");
            Message m = parseMessage(o, false);
            main.post(() -> {
                for (Listener l : copyListeners()) l.onGroupMessage(m);
            });
        } catch (Exception e) {
            Log.e(TAG, "group", e);
        }
    }

    private List<Listener> copyListeners() {
        synchronized (listeners) {
            return new ArrayList<>(listeners);
        }
    }

    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket.off();
            socket = null;
        }
        connected = false;
    }

    public boolean isConnected() {
        return connected && socket != null && socket.connected();
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public void joinGroup(int groupId, String username) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("group_id", groupId);
            o.put("username", username);
            socket.emit("join_group", o);
        } catch (Exception e) {
            Log.e(TAG, "joinGroup", e);
        }
    }

    public void sendGroupMessage(int groupId, String username, String text, Integer replyTo) {
        sendGroupMessage(groupId, username, text, replyTo, "text");
    }

    public void sendGroupMessage(int groupId, String username, String text, Integer replyTo, String type) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("group_id", groupId);
            o.put("username", username);
            o.put("message", text != null ? text : "");
            o.put("type", type != null ? type : "text");
            if (replyTo != null) o.put("reply_to", replyTo);
            socket.emit("send_group_message", o);
        } catch (Exception e) {
            Log.e(TAG, "sendGroup", e);
        }
    }

    public void emit(String event, org.json.JSONObject data) {
        if (socket == null || !socket.connected()) return;
        socket.emit(event, data);
    }


    public void sendButtonCallback(long messageId, String callbackData, String usernameUser) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("message_id", messageId);
            o.put("callback_data", callbackData != null ? callbackData : "");
            o.put("username_user", usernameUser != null ? usernameUser : "");
            socket.emit("button_callback", o);
        } catch (Exception e) {
            Log.e(TAG, "button_callback", e);
        }
    }

    public void sendPrivateMessage(String recipient, String username, String text) {
        sendPrivateMessage(recipient, username, text, "text");
    }

    public void sendPrivateMessage(String recipient, String username, String text, String type) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("recipient", recipient);
            o.put("username", username);
            o.put("message", text != null ? text : "");
            o.put("type", type != null ? type : "text");
            socket.emit("send_private_message", o);
        } catch (Exception e) {
            Log.e(TAG, "sendPrivate", e);
        }
    }

    public void sendTyping(String to) {
        if (socket == null || to == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("to", to);
            socket.emit("typing", o);
        } catch (Exception ignored) {}
    }

    public void sendStopTyping(String to) {
        if (socket == null || to == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("to", to);
            socket.emit("stop_typing", o);
        } catch (Exception ignored) {}
    }

    public void sendDmRead(String partner, long lastReadId) {
        if (socket == null || partner == null || lastReadId <= 0) return;
        try {
            JSONObject o = new JSONObject();
            o.put("partner", partner);
            o.put("last_read_id", lastReadId);
            socket.emit("dm_read", o);
            Log.i(TAG, "dm_read -> " + partner + " #" + lastReadId);
        } catch (Exception ignored) {}
    }

    public void voiceJoin(Integer groupId) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            if (groupId != null) o.put("group_id", groupId);
            socket.emit("voice_join", o);
        } catch (Exception e) {
            Log.e(TAG, "voiceJoin", e);
        }
    }


    public void voiceCall(String target) {
        if (socket == null || target == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("target", target);
            socket.emit("voice_call", o);
        } catch (Exception e) { Log.e(TAG, "voiceCall", e); }
    }

    public void voiceCallAccept(String from) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("from", from);
            socket.emit("voice_call_accept", o);
        } catch (Exception e) { Log.e(TAG, "voiceCallAccept", e); }
    }

    public void voiceCallReject(String from) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("from", from);
            socket.emit("voice_call_reject", o);
        } catch (Exception e) { Log.e(TAG, "voiceCallReject", e); }
    }

    public void voiceCallCancel(String target) {
        if (socket == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("target", target);
            socket.emit("voice_call_cancel", o);
        } catch (Exception e) { Log.e(TAG, "voiceCallCancel", e); }
    }

    public void voiceOffer(String toSid, JSONObject sdp, Integer groupId) {
        if (socket == null || toSid == null || sdp == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("to_sid", toSid);
            o.put("sdp", sdp);
            if (groupId != null) o.put("group_id", groupId);
            socket.emit("voice_offer", o);
        } catch (Exception e) { Log.e(TAG, "voiceOffer", e); }
    }

    public void voiceAnswer(String toSid, JSONObject sdp, Integer groupId) {
        if (socket == null || toSid == null || sdp == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("to_sid", toSid);
            o.put("sdp", sdp);
            if (groupId != null) o.put("group_id", groupId);
            socket.emit("voice_answer", o);
        } catch (Exception e) { Log.e(TAG, "voiceAnswer", e); }
    }

    public void voiceIce(String toSid, JSONObject candidate, Integer groupId) {
        if (socket == null || toSid == null || candidate == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("to_sid", toSid);
            o.put("candidate", candidate);
            if (groupId != null) o.put("group_id", groupId);
            socket.emit("voice_ice", o);
        } catch (Exception e) { Log.e(TAG, "voiceIce", e); }
    }

    public void voiceLeave() {
        if (socket == null) return;
        try {
            socket.emit("voice_leave");
        } catch (Exception ignored) {}
    }

    public void emitRaw(String event, org.json.JSONObject data) {
        if (socket == null) return;
        try {
            socket.emit(event, data);
        } catch (Exception e) {
            Log.e(TAG, "emitRaw " + event, e);
        }
    }

    public void sendReact(long messageId, String emoji) {
        if (socket == null || messageId <= 0) return;
        try {
            JSONObject o = new JSONObject();
            o.put("message_id", messageId);
            o.put("emoji", emoji != null ? emoji : "");
            socket.emit("react", o);
        } catch (Exception ignored) {}
    }


    private JSONObject extractSdp(JSONObject o) {
        if (o == null) return null;
        JSONObject sdp = o.optJSONObject("sdp");
        if (sdp != null) return sdp;
        // sdp may be a JSON string
        String raw = o.optString("sdp", null);
        if (raw != null && raw.startsWith("{")) {
            try { return new JSONObject(raw); } catch (Exception ignored) {}
        }
        // flat type+sdp on root
        if (o.has("type") && o.has("sdp") && !(o.opt("sdp") instanceof JSONObject)) {
            try {
                JSONObject s = new JSONObject();
                s.put("type", o.optString("type"));
                s.put("sdp", o.optString("sdp"));
                return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private JSONObject toJson(Object[] args) {
        if (args == null || args.length == 0) return null;
        if (args[0] instanceof JSONObject) return (JSONObject) args[0];
        try {
            return new JSONObject(String.valueOf(args[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private List<Message.Reaction> parseReactions(JSONArray arr) {
        List<Message.Reaction> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            list.add(new Message.Reaction(r.optString("username"), r.optString("emoji")));
        }
        return list;
    }

    private Message parseMessage(JSONObject o, boolean isPrivate) {
        Message m = new Message();
        m.id = o.optLong("id");
        m.username = o.optString("username", o.optString("sender", ""));
        m.content = o.optString("content", o.optString("message", ""));
        m.message = o.optString("message", m.content);
        m.type = o.optString("type", "text");
        m.mimeType = o.optString("mime_type", null);
        if (m.mimeType != null && "null".equals(m.mimeType)) m.mimeType = null;

        String url = o.optString("url", null);
        String raw = m.content != null ? m.content : m.message;
        if (raw != null) raw = raw.replaceAll("<[^>]+>", "").trim();

        boolean looksMedia = "media".equals(m.type) || "sticker".equals(m.type)
                || (m.mimeType != null && !m.mimeType.isEmpty())
                || (raw != null && (raw.contains("/Uploads/") || raw.contains("/stickers/")));

        if (looksMedia) {
            if (url == null || url.isEmpty()) url = raw;
            if (url != null) {
                java.util.regex.Matcher mm = java.util.regex.Pattern
                        .compile("(https?://\\S+|/Uploads/\\S+|/stickers/\\S+)")
                        .matcher(url);
                if (mm.find()) url = mm.group(1);
                if (url.startsWith("/")) url = ApiClient.BASE + url;
            }
            m.url = url;
            m.content = url;
            m.message = url;
            if (!"sticker".equals(m.type)) m.type = "media";
            if (m.mimeType == null && url != null) {
                String low = url.toLowerCase();
                if (low.contains("/stickers/") || low.matches(".*\\.(jpe?g|png|gif|webp)(\\?.*)?$"))
                    m.mimeType = "image/jpeg";
                else if (low.matches(".*\\.(mp4|webm|mov|3gp)(\\?.*)?$"))
                    m.mimeType = "video/mp4";
                else if (low.matches(".*\\.(mp3|ogg|wav|m4a)(\\?.*)?$"))
                    m.mimeType = "audio/mpeg";
            }
        } else {
            if (m.message != null) m.message = m.message.replaceAll("<[^>]+>", "");
            if (url != null && url.startsWith("/")) url = ApiClient.BASE + url;
            m.url = url;
        }

        m.avatarUrl = o.optString("avatar_url", null);
        if (m.avatarUrl != null && m.avatarUrl.startsWith("/"))
            m.avatarUrl = ApiClient.BASE + m.avatarUrl;
        m.isAdmin = o.optBoolean("is_admin");
        m.isBot = o.optBoolean("is_bot");
        m.isPrivate = isPrivate || o.optBoolean("private");
        m.recipient = o.optString("recipient", null);
        if (o.has("group_id") && !o.isNull("group_id")) m.groupId = o.optInt("group_id");
        if (m.groupId == null && o.has("chat_id") && !o.isNull("chat_id")) m.groupId = -o.optInt("chat_id");
        if (o.has("reply_to") && !o.isNull("reply_to")) m.replyTo = o.optInt("reply_to");
        m.reactions = parseReactions(o.optJSONArray("reactions"));
        m.status = o.optString("status", "sent");
        String me = prefs.getUsername();
        m.isOwn = me != null && me.equals(m.username);
        parseButtonsInto(m, o);

        return m;
    }
}
