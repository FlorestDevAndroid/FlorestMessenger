package ru.florestdev.messenger.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.util.ThemeHelper;

public class StoriesActivity extends AppCompatActivity {
    private LinearLayout rowUsers;
    private LinearLayout detail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1117);

        MaterialToolbar tb = new MaterialToolbar(this);
        tb.setTitle("Истории");
        tb.setTitleTextColor(0xFFE8EAED);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());
        root.addView(tb);

        TextView hint = new TextView(this);
        hint.setText("Загрузка фото/видео — на сайте /stories (24 часа)");
        hint.setTextColor(0xFF8B8F9A);
        hint.setPadding(32, 16, 32, 8);
        root.addView(hint);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        rowUsers = new LinearLayout(this);
        rowUsers.setOrientation(LinearLayout.HORIZONTAL);
        rowUsers.setPadding(24, 16, 24, 16);
        hsv.addView(rowUsers);
        root.addView(hsv);

        detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(24, 8, 24, 24);
        root.addView(detail);

        setContentView(root);
        load();
    }

    private void load() {
        ApiClient.get().fetchStories(new ApiClient.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                runOnUiThread(() -> render(result));
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(StoriesActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void render(String result) {
        rowUsers.removeAllViews();
        detail.removeAllViews();
        try {
            JSONObject j = new JSONObject(result);
            JSONArray feed = j.optJSONArray("feed");
            if (feed == null || feed.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("Нет активных историй.\nОпубликуй на https://messenger.florestone4185.ru/stories");
                empty.setTextColor(0xFFE8EAED);
                detail.addView(empty);
                return;
            }
            for (int i = 0; i < feed.length(); i++) {
                JSONObject u = feed.getJSONObject(i);
                String uname = u.optString("username");
                JSONArray sts = u.optJSONArray("stories");
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(12, 0, 12, 0);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView av = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(120, 120);
                av.setLayoutParams(lp);
                av.setPadding(4, 4, 4, 4);
                Glide.with(this).load(ApiClient.BASE + "/avatar/" + uname).circleCrop().into(av);
                TextView name = new TextView(this);
                name.setText(uname);
                name.setTextColor(0xFFE8EAED);
                name.setGravity(Gravity.CENTER);
                cell.addView(av);
                cell.addView(name);
                final JSONArray stories = sts;
                cell.setOnClickListener(v -> showUserStories(uname, stories));
                rowUsers.addView(cell);
            }
        } catch (Exception e) {
            TextView err = new TextView(this);
            err.setText(result);
            err.setTextColor(0xFFE8EAED);
            detail.addView(err);
        }
    }

    private void showUserStories(String uname, JSONArray stories) {
        detail.removeAllViews();
        TextView title = new TextView(this);
        title.setText("@" + uname);
        title.setTextColor(0xFFA5B4FC);
        title.setTextSize(18);
        detail.addView(title);
        if (stories == null) return;
        for (int i = 0; i < stories.length(); i++) {
            try {
                JSONObject s = stories.getJSONObject(i);
                String url = s.optString("media_url");
                if (url.startsWith("/")) url = ApiClient.BASE + url;
                ImageView img = new ImageView(this);
                img.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 600));
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Glide.with(this).load(url).into(img);
                detail.addView(img);
                TextView cap = new TextView(this);
                cap.setText(s.optString("caption", ""));
                cap.setTextColor(0xFFE8EAED);
                cap.setPadding(0, 8, 0, 24);
                detail.addView(cap);
                int sid = s.optInt("id");
                ApiClient.get().viewStory(sid, new ApiClient.Callback<Boolean>() {
                    @Override public void onSuccess(Boolean r) {}
                    @Override public void onError(String e) {}
                });
            } catch (Exception ignored) {}
        }
    }
}
