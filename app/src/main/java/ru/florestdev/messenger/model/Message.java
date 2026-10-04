package ru.florestdev.messenger.model;

import java.util.ArrayList;
import java.util.List;

public class Message {
    public long id;
    public String type = "text";
    public String username;
    public String content;
    public String message;
    public String mimeType;
    public String url;
    public String avatarUrl;
    public boolean isAdmin;
    public boolean isBot;
    public boolean isPrivate;
    public String recipient;
    public Integer groupId;
    public Integer replyTo;
    public String timestamp;
    public boolean isOwn;
    /** sent | delivered | read */
    public String status = "sent";
    public List<Reaction> reactions = new ArrayList<>();
    /** Inline buttons from bots: {text, url?} or {text, callback_data?} */
    public List<MsgButton> buttons = new ArrayList<>();

    public static class MsgButton {
        public String text;
        public String url;
        public String callbackData;
        public boolean isUrl() { return url != null && !url.isEmpty(); }
        public boolean isCallback() { return callbackData != null && !callbackData.isEmpty(); }
    }

    public static class Reaction {
        public String username;
        public String emoji;
        public Reaction() {}
        public Reaction(String u, String e) { username = u; emoji = e; }
    }

    public String displayText() {
        if (message != null && !message.isEmpty()) return message;
        if (content != null) return content;
        return "";
    }

    public String reactionsSummary() {
        if (reactions == null || reactions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Reaction r : reactions) {
            if (r.emoji == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(r.emoji);
        }
        return sb.toString();
    }
}
