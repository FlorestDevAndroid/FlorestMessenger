package ru.florestdev.messenger.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

public class Prefs {
    private static final String NAME = "florest_prefs";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public void setUsername(String u) { sp.edit().putString("username", u).apply(); }
    public String getUsername() { return sp.getString("username", null); }

    public void setLoggedIn(boolean v) { sp.edit().putBoolean("logged_in", v).apply(); }
    public boolean isLoggedIn() { return sp.getBoolean("logged_in", false); }

    /** ID групп, в которых пользователь состоит */
    public void setMemberGroupIds(Set<Integer> ids) {
        JSONArray arr = new JSONArray();
        if (ids != null) {
            for (Integer id : ids) {
                if (id != null) arr.put(id);
            }
        }
        sp.edit().putString("member_groups", arr.toString()).apply();
    }

    public Set<Integer> getMemberGroupIds() {
        Set<Integer> out = new HashSet<>();
        String raw = sp.getString("member_groups", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getInt(i));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void addMemberGroup(int groupId) {
        Set<Integer> s = getMemberGroupIds();
        s.add(groupId);
        setMemberGroupIds(s);
    }

    public void setGroupName(int groupId, String name) {
        sp.edit().putString("group_name_" + groupId, name != null ? name : "").apply();
    }

    public String getGroupName(int groupId) {
        return sp.getString("group_name_" + groupId, "Группа #" + groupId);
    }

    /** Текущий открытый чат — чтобы не слать уведомление самому себе на экране */
    public void setActiveChat(String key) {
        sp.edit().putString("active_chat", key != null ? key : "").apply();
    }

    public String getActiveChat() {
        return sp.getString("active_chat", "");
    }

    public static String dmKey(String partner) {
        return "dm:" + (partner != null ? partner : "");
    }

    public static String groupKey(int groupId) {
        return "group:" + groupId;
    }

    public void clear() { sp.edit().clear().apply(); }

    public String getTheme() {
        return sp.getString("ui_theme", "dark");
    }
    public void setTheme(String theme) {
        sp.edit().putString("ui_theme", theme != null ? theme : "dark").apply();
    }

    public void setFcmToken(String token) {
        sp.edit().putString("fcm_token", token != null ? token : "").apply();
    }

    public String getFcmToken() {
        return sp.getString("fcm_token", null);
    }
}
