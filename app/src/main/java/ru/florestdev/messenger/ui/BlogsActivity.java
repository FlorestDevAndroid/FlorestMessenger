package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.adapter.BlogAdapter;
import ru.florestdev.messenger.model.BlogPost;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.util.Prefs;

public class BlogsActivity extends AppCompatActivity implements BlogAdapter.Listener {
    private BlogAdapter adapter;
    private SwipeRefreshLayout swipe;
    private String me;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        // use coordinator-less layout; add FAB programmatically via menu on toolbar
        setContentView(R.layout.activity_blogs);

        me = new Prefs(this).getUsername();

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());
        tb.inflateMenu(R.menu.menu_blogs);
        tb.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_new_post) {
                showCreateDialog();
                return true;
            }
            return false;
        });

        swipe = findViewById(R.id.swipeRefresh);
        RecyclerView rv = findViewById(R.id.rvPosts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BlogAdapter(me, this);
        rv.setAdapter(adapter);

        swipe.setOnRefreshListener(this::load);
        load();
    }

    private void load() {
        swipe.setRefreshing(true);
        ApiClient.get().fetchBlogs(new ApiClient.Callback<List<BlogPost>>() {
            @Override
            public void onSuccess(List<BlogPost> result) {
                runOnUiThread(() -> {
                    swipe.setRefreshing(false);
                    adapter.setItems(result);
                    if (result == null || result.isEmpty()) {
                        Toast.makeText(BlogsActivity.this, "Постов пока нет", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    swipe.setRefreshing(false);
                    Toast.makeText(BlogsActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showCreateDialog() {
        EditText etTitle = new EditText(this);
        etTitle.setHint("Заголовок");
        EditText etContent = new EditText(this);
        etContent.setHint("Текст");
        etContent.setMinLines(4);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.addView(etTitle);
        box.addView(etContent);

        new AlertDialog.Builder(this)
                .setTitle("Новый пост")
                .setView(box)
                .setPositiveButton("Опубликовать", (d, w) -> {
                    String title = etTitle.getText().toString().trim();
                    String content = etContent.getText().toString().trim();
                    if (title.isEmpty() || content.isEmpty()) {
                        Toast.makeText(this, "Заполни заголовок и текст", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.get().createBlogPost(title, content, new ApiClient.Callback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            runOnUiThread(() -> {
                                Toast.makeText(BlogsActivity.this, "Пост создан", Toast.LENGTH_SHORT).show();
                                load();
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> Toast.makeText(BlogsActivity.this, error, Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onLike(BlogPost post) {
        ApiClient.get().likeBlogPost(post.id, new ApiClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(() -> load());
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(BlogsActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onComment(BlogPost post, String text) {
        ApiClient.get().commentBlogPost(post.id, text, new ApiClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(() -> load());
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(BlogsActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onDelete(BlogPost post) {
        new AlertDialog.Builder(this)
                .setMessage("Удалить пост?")
                .setPositiveButton("Удалить", (d, w) ->
                        ApiClient.get().deleteBlogPost(post.id, new ApiClient.Callback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean result) {
                                runOnUiThread(() -> {
                                    Toast.makeText(BlogsActivity.this, "Удалено", Toast.LENGTH_SHORT).show();
                                    load();
                                });
                            }

                            @Override
                            public void onError(String error) {
                                runOnUiThread(() -> Toast.makeText(BlogsActivity.this, error, Toast.LENGTH_SHORT).show());
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
