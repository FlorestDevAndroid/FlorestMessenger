package ru.florestdev.messenger.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.model.Group;
import ru.florestdev.messenger.network.ApiClient;

public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
    public interface OnAction {
        void onOpen(Group g);
        void onJoin(Group g);
    }

    private final List<Group> items = new ArrayList<>();
    private final OnAction action;

    public GroupAdapter(OnAction action) {
        this.action = action;
    }

    public void setItems(List<Group> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Group g = items.get(position);
        h.tvName.setText(g.name);
        h.tvCreator.setText(g.creator != null ? "создал " + g.creator : "");
        h.tvDesc.setText(g.description != null && !g.description.isEmpty() ? g.description : "Без описания");
        String av = g.avatarUrl;
        if (av == null || av.isEmpty()) av = ApiClient.get().groupAvatarUrl(g.id);
        Glide.with(h.ivAvatar).load(av)
                .transform(new RoundedCorners(24))
                .placeholder(android.R.drawable.sym_def_app_icon)
                .into(h.ivAvatar);
        if (g.isMember) {
            h.btnAction.setText(R.string.open);
            h.btnAction.setOnClickListener(v -> action.onOpen(g));
        } else {
            h.btnAction.setText(R.string.join);
            h.btnAction.setOnClickListener(v -> action.onJoin(g));
        }
        h.itemView.setOnClickListener(v -> {
            if (g.isMember) action.onOpen(g);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView tvName, tvCreator, tvDesc;
        MaterialButton btnAction;
        VH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.ivAvatar);
            tvName = v.findViewById(R.id.tvName);
            tvCreator = v.findViewById(R.id.tvCreator);
            tvDesc = v.findViewById(R.id.tvDesc);
            btnAction = v.findViewById(R.id.btnAction);
        }
    }
}
