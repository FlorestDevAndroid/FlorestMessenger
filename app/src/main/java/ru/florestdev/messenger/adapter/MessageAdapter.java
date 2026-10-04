package ru.florestdev.messenger.adapter;

import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.Button;
import android.view.ViewGroup;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.util.Prefs;

import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;

import java.util.ArrayList;
import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.model.Message;
import ru.florestdev.messenger.network.ApiClient;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface LongClickListener {
        void onMessageLongClick(Message m);
    }

    private static final int TYPE_OWN = 1;
    private static final int TYPE_OTHER = 2;

    private final List<Message> items = new ArrayList<>();
    private LongClickListener longClickListener;
    private String currentUsername;
    private long theirLastReadId = 0;
    private MediaPlayer sharedPlayer;
    private int playingAudioPos = -1;

    public MessageAdapter() {}

    public MessageAdapter(String username) {
        this.currentUsername = username;
    }

    public void setLongClickListener(LongClickListener l) {
        this.longClickListener = l;
    }

    public int size() {
        return items.size();
    }

    public void setItems(List<Message> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setMessages(List<Message> list) {
        setItems(list);
    }

    public void add(Message m) {
        addMessage(m);
    }

    public void addMessage(Message m) {
        if (m == null) return;
        if (m.id > 0) {
            for (int i = 0; i < items.size(); i++) {
                Message x = items.get(i);
                if (x.id == m.id) {
                    if (m.reactions != null && !m.reactions.isEmpty()) x.reactions = m.reactions;
                    if (m.buttons != null && !m.buttons.isEmpty()) x.buttons = m.buttons;
                    if (m.status != null) x.status = m.status;
                    notifyItemChanged(i);
                    return;
                }
            }
        }
        String text = normalize(m.displayText());
        if (m.isOwn && text.length() > 0 && !isMediaMessage(m)) {
            for (int i = items.size() - 1; i >= 0 && i >= items.size() - 8; i--) {
                Message x = items.get(i);
                if (x.isOwn && text.equals(normalize(x.displayText()))) {
                    if (x.id <= 0 && m.id > 0) {
                        items.set(i, m);
                        notifyItemChanged(i);
                        return;
                    }
                    if (x.id > 0 && m.id > 0 && x.id == m.id) return;
                }
            }
        }
        items.add(m);
        notifyItemInserted(items.size() - 1);
    }

    public long maxIncomingId() {
        long max = 0;
        for (Message m : items) {
            if (!m.isOwn && m.id > max) max = m.id;
        }
        return max;
    }

    public void setTheirLastReadId(long id) {
        if (id <= theirLastReadId) return;
        theirLastReadId = id;
        for (int i = 0; i < items.size(); i++) {
            Message m = items.get(i);
            if (m.isOwn && m.id > 0 && m.id <= theirLastReadId) {
                if (!"read".equals(m.status)) {
                    m.status = "read";
                    notifyItemChanged(i);
                }
            }
        }
    }

    public void updateReactions(long messageId, List<Message.Reaction> reactions) {
        for (int i = 0; i < items.size(); i++) {
            Message m = items.get(i);
            if (m.id == messageId) {
                m.reactions = reactions != null ? reactions : new ArrayList<>();
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void updateStatus(long messageId, String status) {
        for (int i = 0; i < items.size(); i++) {
            Message m = items.get(i);
            if (m.id == messageId) {
                m.status = status;
                notifyItemChanged(i);
                return;
            }
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").trim();
    }

    static String mediaUrl(Message m) {
        String u = m.url;
        if (u == null || u.isEmpty()) {
            String c = m.content != null ? m.content : m.message;
            if (c != null) {
                c = c.replaceAll("<[^>]+>", "").trim();
                if (c.contains("/Uploads/") || c.contains("/stickers/") || c.startsWith("http"))
                    u = c;
            }
        }
        if (u == null || u.isEmpty()) return null;
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("(https?://\\S+|/Uploads/\\S+|/stickers/\\S+)")
                .matcher(u);
        if (mm.find()) u = mm.group(1);
        // trim trailing punctuation
        while (u.length() > 0 && ".,);]>\"'".indexOf(u.charAt(u.length() - 1)) >= 0)
            u = u.substring(0, u.length() - 1);
        if (u.startsWith("/")) u = ApiClient.BASE + u;
        return u;
    }

    static boolean isMediaMessage(Message m) {
        if (m == null) return false;
        if ("media".equals(m.type) || "sticker".equals(m.type)) return true;
        if (m.mimeType != null && !m.mimeType.isEmpty() && !"null".equals(m.mimeType)) return true;
        String c = m.displayText();
        return c != null && (c.contains("/Uploads/") || c.contains("/stickers/"));
    }

    static String guessMime(Message m, String url) {
        if (m.mimeType != null && !m.mimeType.isEmpty() && !"null".equals(m.mimeType))
            return m.mimeType;
        if (url == null) return "";
        String low = url.toLowerCase();
        if (low.contains("/stickers/")) return "image/sticker";
        if (low.matches(".*\\.(jpe?g|png|gif|webp|bmp)(\\?.*)?$")) return "image/jpeg";
        if (low.matches(".*\\.(mp4|webm|mov|mkv|3gp)(\\?.*)?$")) return "video/mp4";
        if (low.matches(".*\\.(mp3|ogg|wav|m4a|aac|opus)(\\?.*)?$")) return "audio/mpeg";
        return "application/octet-stream";
    }

    static boolean isImage(String mime) {
        return mime != null && (mime.startsWith("image/") || "image/sticker".equals(mime));
    }

    static boolean isVideo(String mime) {
        return mime != null && mime.startsWith("video/");
    }

    static boolean isAudio(String mime) {
        return mime != null && mime.startsWith("audio/");
    }

    private void stopSharedPlayer() {
        try {
            if (sharedPlayer != null) {
                if (sharedPlayer.isPlaying()) sharedPlayer.stop();
                sharedPlayer.release();
            }
        } catch (Exception ignored) {
        }
        sharedPlayer = null;
        playingAudioPos = -1;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).isOwn ? TYPE_OWN : TYPE_OTHER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_OWN) {
            return new OwnVH(inf.inflate(R.layout.item_message_own, parent, false));
        }
        return new OtherVH(inf.inflate(R.layout.item_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message m = items.get(position);
        if (holder instanceof OwnVH) ((OwnVH) holder).bind(m, position);
        else ((OtherVH) holder).bind(m, position);
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) longClickListener.onMessageLongClick(m);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        MediaViews mv = null;
        if (holder instanceof OwnVH) mv = ((OwnVH) holder).media;
        else if (holder instanceof OtherVH) mv = ((OtherVH) holder).media;
        if (mv != null) {
            try {
                if (mv.videoView != null) {
                    mv.videoView.stopPlayback();
                }
            } catch (Exception ignored) {
            }
            if (mv.btnVideoPlay != null) mv.btnVideoPlay.setVisibility(View.VISIBLE);
        }
    }

    static class MediaViews {
        ImageView ivMedia;
        FrameLayout videoContainer;
        VideoView videoView;
        ImageButton btnVideoPlay;
        LinearLayout audioContainer;
        ImageButton btnAudioPlay;
        TextView tvAudioLabel;
        TextView btnDownload;
        TextView tvContent;
        TextView tvReactions;
    }

    private void bindMedia(MediaViews v, Message m, int position) {
        // reset
        if (v.ivMedia != null) {
            v.ivMedia.setVisibility(View.GONE);
            v.ivMedia.setOnClickListener(null);
        }
        if (v.videoContainer != null) v.videoContainer.setVisibility(View.GONE);
        if (v.audioContainer != null) v.audioContainer.setVisibility(View.GONE);
        if (v.btnDownload != null) {
            v.btnDownload.setVisibility(View.GONE);
            v.btnDownload.setOnClickListener(null);
        }
        if (v.tvContent != null) {
            v.tvContent.setVisibility(View.VISIBLE);
            v.tvContent.setOnClickListener(null);
        }

        if (!isMediaMessage(m)) {
            if (v.tvContent != null) {
                v.tvContent.setText(m.displayText() != null ? m.displayText() : "");
            }
            bindReactions(v.tvReactions, m);
            return;
        }

        String url = mediaUrl(m);
        String mime = guessMime(m, url);
        if (url == null) {
            if (v.tvContent != null) v.tvContent.setText(m.displayText());
            bindReactions(v.tvReactions, m);
            return;
        }

        if (isImage(mime)) {
            if (v.tvContent != null) v.tvContent.setVisibility(View.GONE);
            if (v.ivMedia != null) {
                v.ivMedia.setVisibility(View.VISIBLE);
                Glide.with(v.ivMedia.getContext())
                        .load(url)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_report_image)
                        .into(v.ivMedia);
            }
        } else if (isVideo(mime)) {
            if (v.tvContent != null) v.tvContent.setVisibility(View.GONE);
            if (v.videoContainer != null && v.videoView != null) {
                v.videoContainer.setVisibility(View.VISIBLE);
                if (v.btnVideoPlay != null) v.btnVideoPlay.setVisibility(View.VISIBLE);
                final String videoUrl = url;
                final VideoView vv = v.videoView;
                final ImageButton playBtn = v.btnVideoPlay;
                // prepare on play click to avoid autoplay on scroll
                vv.setOnPreparedListener(mp -> {
                    mp.setLooping(false);
                    try {
                        // scale
                        mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    } catch (Exception ignored) {
                    }
                });
                vv.setOnCompletionListener(mp -> {
                    if (playBtn != null) playBtn.setVisibility(View.VISIBLE);
                });
                vv.setOnErrorListener((mp, what, extra) -> {
                    if (playBtn != null) playBtn.setVisibility(View.VISIBLE);
                    Toast.makeText(vv.getContext(), "Не удалось воспроизвести видео", Toast.LENGTH_SHORT).show();
                    return true;
                });
                View.OnClickListener play = click -> {
                    try {
                        if (vv.isPlaying()) {
                            vv.pause();
                            if (playBtn != null) playBtn.setVisibility(View.VISIBLE);
                        } else {
                            if (playBtn != null) playBtn.setVisibility(View.GONE);
                            Uri uri = Uri.parse(videoUrl);
                            vv.setVideoURI(uri);
                            MediaController mc = new MediaController(vv.getContext());
                            mc.setAnchorView(vv);
                            vv.setMediaController(mc);
                            vv.start();
                        }
                    } catch (Exception e) {
                        Toast.makeText(vv.getContext(), "Ошибка видео", Toast.LENGTH_SHORT).show();
                        if (playBtn != null) playBtn.setVisibility(View.VISIBLE);
                    }
                };
                if (playBtn != null) playBtn.setOnClickListener(play);
                vv.setOnClickListener(play);
            }
        } else if (isAudio(mime)) {
            if (v.tvContent != null) v.tvContent.setVisibility(View.GONE);
            if (v.audioContainer != null) {
                v.audioContainer.setVisibility(View.VISIBLE);
                if (v.tvAudioLabel != null) v.tvAudioLabel.setText("🎵 Аудиосообщение");
                final String audioUrl = url;
                final int pos = position;
                final ImageButton btn = v.btnAudioPlay;
                if (btn != null) {
                    btn.setImageResource(android.R.drawable.ic_media_play);
                    btn.setOnClickListener(click -> {
                        try {
                            if (playingAudioPos == pos && sharedPlayer != null && sharedPlayer.isPlaying()) {
                                sharedPlayer.pause();
                                btn.setImageResource(android.R.drawable.ic_media_play);
                                return;
                            }
                            stopSharedPlayer();
                            sharedPlayer = new MediaPlayer();
                            sharedPlayer.setAudioAttributes(
                                    new AudioAttributes.Builder()
                                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                            .setUsage(AudioAttributes.USAGE_MEDIA)
                                            .build());
                            sharedPlayer.setDataSource(audioUrl);
                            sharedPlayer.setOnPreparedListener(mp -> {
                                mp.start();
                                playingAudioPos = pos;
                                btn.setImageResource(android.R.drawable.ic_media_pause);
                            });
                            sharedPlayer.setOnCompletionListener(mp -> {
                                btn.setImageResource(android.R.drawable.ic_media_play);
                                stopSharedPlayer();
                            });
                            sharedPlayer.setOnErrorListener((mp, w, e) -> {
                                Toast.makeText(btn.getContext(), "Ошибка аудио", Toast.LENGTH_SHORT).show();
                                stopSharedPlayer();
                                btn.setImageResource(android.R.drawable.ic_media_play);
                                return true;
                            });
                            sharedPlayer.prepareAsync();
                        } catch (Exception e) {
                            Toast.makeText(btn.getContext(), "Не удалось воспроизвести", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        } else {
            // generic file — download
            if (v.tvContent != null) {
                v.tvContent.setVisibility(View.VISIBLE);
                String name = url;
                int slash = url.lastIndexOf('/');
                if (slash >= 0 && slash < url.length() - 1) name = url.substring(slash + 1);
                v.tvContent.setText("📎 " + name);
            }
            if (v.btnDownload != null) {
                v.btnDownload.setVisibility(View.VISIBLE);
                final String fileUrl = url;
                v.btnDownload.setOnClickListener(click -> {
                    try {
                        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(fileUrl));
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        click.getContext().startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(click.getContext(), "Не удалось открыть файл", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        bindReactions(v.tvReactions, m);
    }


    private void bindButtons(LinearLayout layout, Message m) {
        if (layout == null) return;
        layout.removeAllViews();
        if (m.buttons == null || m.buttons.isEmpty()) {
            layout.setVisibility(View.GONE);
            return;
        }
        layout.setVisibility(View.VISIBLE);
        String me = currentUsername != null ? currentUsername : new Prefs(layout.getContext()).getUsername();
        for (Message.MsgButton b : m.buttons) {
            if (b == null || b.text == null) continue;
            com.google.android.material.button.MaterialButton btn =
                    new com.google.android.material.button.MaterialButton(
                            layout.getContext(), null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText(b.text);
            btn.setAllCaps(false);
            btn.setTextSize(13f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 4;
            btn.setLayoutParams(lp);
            if (b.isUrl()) {
                final String url = b.url;
                btn.setOnClickListener(v -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        v.getContext().startActivity(i);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(v.getContext(), "Не открыть ссылку", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            } else if (b.isCallback()) {
                final String cb = b.callbackData;
                final long mid = m.id;
                btn.setOnClickListener(v -> {
                    SocketManager.get().sendButtonCallback(mid, cb, me);
                    android.widget.Toast.makeText(v.getContext(), "Отправлено", android.widget.Toast.LENGTH_SHORT).show();
                });
            } else {
                continue;
            }
            layout.addView(btn);
        }
        if (layout.getChildCount() == 0) layout.setVisibility(View.GONE);
    }

    static void bindReactions(TextView tvReactions, Message m) {
        if (tvReactions == null) return;
        String s = m.reactionsSummary();
        if (s == null || s.isEmpty()) {
            tvReactions.setVisibility(View.GONE);
        } else {
            tvReactions.setVisibility(View.VISIBLE);
            tvReactions.setText(s);
        }
    }

    class OwnVH extends RecyclerView.ViewHolder {
        LinearLayout layoutButtons;
        TextView tvStatus;
        MediaViews media = new MediaViews();

        OwnVH(View v) {
            super(v);
            media.ivMedia = v.findViewById(R.id.ivMedia);
            // fallback old id
            if (media.ivMedia == null) {
                View st = v.findViewById(R.id.ivSticker);
                if (st instanceof ImageView) media.ivMedia = (ImageView) st;
            }
            media.videoContainer = v.findViewById(R.id.videoContainer);
            media.videoView = v.findViewById(R.id.videoView);
            media.btnVideoPlay = v.findViewById(R.id.btnVideoPlay);
            media.audioContainer = v.findViewById(R.id.audioContainer);
            media.btnAudioPlay = v.findViewById(R.id.btnAudioPlay);
            media.tvAudioLabel = v.findViewById(R.id.tvAudioLabel);
            media.btnDownload = v.findViewById(R.id.btnDownload);
            media.tvContent = v.findViewById(R.id.tvContent);
            media.tvReactions = v.findViewById(R.id.tvReactions);
            layoutButtons = v.findViewById(R.id.layoutButtons);
            tvStatus = v.findViewById(R.id.tvStatus);
        }

        void bind(Message m, int position) {
            bindMedia(media, m, position);
            bindButtons(layoutButtons, m);
            if (tvStatus != null) {
                if ("read".equals(m.status) || (m.id > 0 && m.id <= theirLastReadId)) {
                    tvStatus.setText("✓✓");
                    tvStatus.setTextColor(0xFF90CAF9);
                    tvStatus.setTypeface(null, Typeface.BOLD);
                } else if ("delivered".equals(m.status)) {
                    tvStatus.setText("✓✓");
                    tvStatus.setTextColor(0xCCFFFFFF);
                    tvStatus.setTypeface(null, Typeface.NORMAL);
                } else {
                    tvStatus.setText("✓");
                    tvStatus.setTextColor(0x99FFFFFF);
                    tvStatus.setTypeface(null, Typeface.NORMAL);
                }
            }
        }
    }

    class OtherVH extends RecyclerView.ViewHolder {
        LinearLayout layoutButtons;
        ImageView ivAvatar;
        TextView tvUsername;
        MediaViews media = new MediaViews();

        OtherVH(View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.ivAvatar);
            tvUsername = v.findViewById(R.id.tvUsername);
            media.ivMedia = v.findViewById(R.id.ivMedia);
            if (media.ivMedia == null) {
                View st = v.findViewById(R.id.ivSticker);
                if (st instanceof ImageView) media.ivMedia = (ImageView) st;
            }
            media.videoContainer = v.findViewById(R.id.videoContainer);
            media.videoView = v.findViewById(R.id.videoView);
            media.btnVideoPlay = v.findViewById(R.id.btnVideoPlay);
            media.audioContainer = v.findViewById(R.id.audioContainer);
            media.btnAudioPlay = v.findViewById(R.id.btnAudioPlay);
            media.tvAudioLabel = v.findViewById(R.id.tvAudioLabel);
            media.btnDownload = v.findViewById(R.id.btnDownload);
            media.tvContent = v.findViewById(R.id.tvContent);
            media.tvReactions = v.findViewById(R.id.tvReactions);
            layoutButtons = v.findViewById(R.id.layoutButtons);
        }

        void bind(Message m, int position) {
            if (tvUsername != null)
                tvUsername.setText(m.username != null ? m.username : "");
            bindMedia(media, m, position);
            bindButtons(layoutButtons, m);
            String url = m.avatarUrl;
            if (url == null || url.isEmpty()) {
                url = ApiClient.get().avatarUrl(m.username != null ? m.username : "system");
            }
            if (ivAvatar != null) {
                Glide.with(ivAvatar.getContext())
                        .load(url)
                        .transform(new CircleCrop())
                        .placeholder(android.R.drawable.sym_def_app_icon)
                        .into(ivAvatar);
            }
        }
    }
}
