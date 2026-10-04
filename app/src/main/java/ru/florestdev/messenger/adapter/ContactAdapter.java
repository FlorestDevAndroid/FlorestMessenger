package ru.florestdev.messenger.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;

import java.util.ArrayList;
import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.model.Contact;
import ru.florestdev.messenger.network.ApiClient;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {
    public interface OnClick {
        void onContact(Contact c);
    }

    private final List<Contact> all = new ArrayList<>();
    private final List<Contact> filtered = new ArrayList<>();
    private final OnClick onClick;

    public ContactAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void setItems(List<Contact> list) {
        all.clear();
        filtered.clear();
        if (list != null) {
            all.addAll(list);
            filtered.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void filter(String q) {
        filtered.clear();
        if (q == null || q.trim().isEmpty()) {
            filtered.addAll(all);
        } else {
            String lower = q.toLowerCase();
            for (Contact c : all) {
                if (c.username.toLowerCase().contains(lower)) filtered.add(c);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Contact c = filtered.get(position);
        h.tvName.setText(c.username);
        if (c.bot) h.tvStatus.setText("бот");
        else if (c.online) h.tvStatus.setText("онлайн");
        else h.tvStatus.setText("");
        Glide.with(h.ivAvatar)
                .load(ApiClient.get().avatarUrl(c.username))
                .transform(new CircleCrop())
                .placeholder(android.R.drawable.sym_def_app_icon)
                .into(h.ivAvatar);
        h.itemView.setOnClickListener(v -> onClick.onContact(c));
    }

    @Override
    public int getItemCount() {
        return filtered.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView tvName, tvStatus;
        VH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.ivAvatar);
            tvName = v.findViewById(R.id.tvName);
            tvStatus = v.findViewById(R.id.tvStatus);
        }
    }
}
