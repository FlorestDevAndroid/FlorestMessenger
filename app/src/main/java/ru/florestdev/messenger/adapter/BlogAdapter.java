package ru.florestdev.messenger.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.model.BlogPost;
import ru.florestdev.messenger.network.ApiClient;

public class BlogAdapter extends RecyclerView.Adapter<BlogAdapter.VH> {
    public interface Listener {
        void onLike(BlogPost post);
        void onComment(BlogPost post, String text);
        void onDelete(BlogPost post);
    }

    private final List<BlogPost> items = new ArrayList<>();
    private final Listener listener;
    private final String me;

    public BlogAdapter(String me, Listener listener) {
        this.me = me;
        this.listener = listener;
    }

    public void setItems(List<BlogPost> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_blog_post, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        BlogPost p = items.get(position);
        h.tvTitle.setText(p.title != null ? p.title : "");
        String meta = (p.author != null ? p.author : "") +
                (p.createdAt != null ? " · " + p.createdAt : "");
        h.tvMeta.setText(meta);
        h.tvContent.setText(p.content != null ? p.content : "");
        h.btnLike.setText(p.likesCount > 0
                ? (p.liked ? "♥ " + p.likesCount : "♡ " + p.likesCount)
                : (p.liked ? "♥ Лайк" : "♡ Лайк"));
        boolean canDelete = me != null && me.equals(p.author);
        h.btnDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);

        Glide.with(h.ivAvatar)
                .load(ApiClient.get().avatarUrl(p.author != null ? p.author : "system"))
                .transform(new CircleCrop())
                .into(h.ivAvatar);

        h.commentsBox.removeAllViews();
        if (p.comments != null) {
            for (BlogPost.Comment c : p.comments) {
                TextView tv = new TextView(h.commentsBox.getContext());
                tv.setTextColor(0xFF94A3B8);
                tv.setTextSize(13f);
                tv.setPadding(0, 4, 0, 4);
                String line = (c.username != null ? c.username : "") + ": " +
                        (c.content != null ? c.content : "");
                tv.setText(line);
                h.commentsBox.addView(tv);
            }
        }

        h.btnLike.setOnClickListener(v -> listener.onLike(p));
        h.btnDelete.setOnClickListener(v -> listener.onDelete(p));
        h.btnComment.setOnClickListener(v -> {
            String t = h.etComment.getText() != null ? h.etComment.getText().toString().trim() : "";
            if (!t.isEmpty()) {
                listener.onComment(p, t);
                h.etComment.setText("");
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView tvTitle, tvMeta, tvContent;
        MaterialButton btnLike, btnDelete, btnComment;
        LinearLayout commentsBox;
        EditText etComment;

        VH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.ivAvatar);
            tvTitle = v.findViewById(R.id.tvTitle);
            tvMeta = v.findViewById(R.id.tvMeta);
            tvContent = v.findViewById(R.id.tvContent);
            btnLike = v.findViewById(R.id.btnLike);
            btnDelete = v.findViewById(R.id.btnDelete);
            btnComment = v.findViewById(R.id.btnComment);
            commentsBox = v.findViewById(R.id.commentsBox);
            etComment = v.findViewById(R.id.etComment);
        }
    }
}
