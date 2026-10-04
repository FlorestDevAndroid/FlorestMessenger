package ru.florestdev.messenger.util;

import android.app.Activity;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

import ru.florestdev.messenger.R;

public final class ThemeHelper {
    private ThemeHelper() {}

    public static int themeResId(String theme) {
        if (theme == null) theme = "dark";
        switch (theme) {
            case "light": return R.style.Theme_Florest_Light;
            case "amoled": return R.style.Theme_Florest_Amoled;
            case "blue": return R.style.Theme_Florest_Blue;
            case "purple": return R.style.Theme_Florest_Purple;
            case "green": return R.style.Theme_Florest_Green;
            case "dark":
            default: return R.style.Theme_Florest_Dark;
        }
    }

    public static void applyToActivity(Activity activity) {
        Prefs p = new Prefs(activity);
        String theme = p.getTheme();
        if ("light".equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
        activity.setTheme(themeResId(theme));
    }

    public static void applyFromPrefs(Context ctx) {
        Prefs p = new Prefs(ctx);
        String theme = p.getTheme();
        if ("light".equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
    }
}
