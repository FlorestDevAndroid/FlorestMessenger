package ru.florestdev.messenger.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.call.WebRtcClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.util.NotificationHelper;
import ru.florestdev.messenger.util.ThemeHelper;

/**
 * DM voice call. Critical: NEVER emit voice_leave / cancel from onDestroy —
 * web treats voice_user_left as "peer ended the call".
 */
public class CallActivity extends AppCompatActivity implements SocketManager.Listener, WebRtcClient.Signaling {
    private static final String TAG = "CallActivity";

    public static final String EXTRA_PARTNER = "partner";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FROM_SID = "from_sid";
    public static final String MODE_OUTGOING = "outgoing";
    public static final String MODE_INCOMING = "incoming";

    private static CallActivity activeInstance;

    private String partner;
    private String mode;
    private String partnerSid;
    private boolean outgoing;
    private boolean inCall;
    private boolean connectedMedia;
    private boolean intentionalEnd;
    private boolean offerSent;
    private boolean joinedVoice;
    private boolean started;

    private TextView tvStatus;
    private TextView tvPartner;
    private MaterialButton btnAccept, btnReject, btnHangup;
    private WebRtcClient rtc;
    private AudioManager audioManager;
    private int prevAudioMode = AudioManager.MODE_NORMAL;
    private boolean prevSpeaker;

    private final Handler main = new Handler(Looper.getMainLooper());
    private long seconds;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!inCall || tvStatus == null) return;
            seconds++;
            tvStatus.setText(String.format("%d:%02d", seconds / 60, seconds % 60));
            main.postDelayed(this, 1000);
        }
    };

    public static void startOutgoing(Context ctx, String partner) {
        Intent i = new Intent(ctx, CallActivity.class);
        i.putExtra(EXTRA_PARTNER, partner);
        i.putExtra(EXTRA_MODE, MODE_OUTGOING);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }

    public static void startIncoming(Context ctx, String from, String fromSid) {
        // Prefer existing instance via singleTop
        Intent i = new Intent(ctx, CallActivity.class);
        i.putExtra(EXTRA_PARTNER, from);
        i.putExtra(EXTRA_MODE, MODE_INCOMING);
        i.putExtra(EXTRA_FROM_SID, fromSid);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ctx.startActivity(i);
    }

    public static boolean isInCallWith(String user) {
        return activeInstance != null && user != null && user.equals(activeInstance.partner);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        activeInstance = this;
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        setContentView(R.layout.activity_call);

        applyIntent(getIntent());

        tvPartner = findViewById(R.id.tvPartner);
        tvStatus = findViewById(R.id.tvStatus);
        btnAccept = findViewById(R.id.btnAccept);
        btnReject = findViewById(R.id.btnReject);
        btnHangup = findViewById(R.id.btnHangup);

        tvPartner.setText(partner != null ? partner : "?");
        updateChrome();

        btnAccept.setOnClickListener(v -> accept());
        btnReject.setOnClickListener(v -> reject());
        btnHangup.setOnClickListener(v -> endCallByUser());

        SocketManager.get().addListener(this);
        // Do NOT force reconnect — existing session socket must stay alive
        if (!SocketManager.get().isConnected()) {
            SocketManager.get().connect();
        }

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 42);
        } else {
            startLogic();
            if (getIntent().getBooleanExtra("auto_accept", false) && !outgoing) {
                accept();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Already in a call UI — just refresh partner/sid if needed
        if (intent != null) {
            String p = intent.getStringExtra(EXTRA_PARTNER);
            String sid = intent.getStringExtra(EXTRA_FROM_SID);
            if (p != null) partner = p;
            if (sid != null && !sid.isEmpty()) partnerSid = sid;
            if (tvPartner != null) tvPartner.setText(partner);
            if (intent.getBooleanExtra("auto_accept", false) && !outgoing && !inCall) {
                accept();
            }
        }
    }

    private void applyIntent(Intent intent) {
        if (intent == null) return;
        partner = intent.getStringExtra(EXTRA_PARTNER);
        mode = intent.getStringExtra(EXTRA_MODE);
        partnerSid = intent.getStringExtra(EXTRA_FROM_SID);
        outgoing = MODE_OUTGOING.equals(mode);
    }

    private void updateChrome() {
        if (inCall) {
            tvStatus.setText(connectedMedia ? "Разговор" : "Соединение…");
            btnAccept.setVisibility(android.view.View.GONE);
            btnReject.setVisibility(android.view.View.GONE);
            btnHangup.setVisibility(android.view.View.VISIBLE);
        } else if (outgoing) {
            tvStatus.setText("Вызов…");
            btnAccept.setVisibility(android.view.View.GONE);
            btnReject.setVisibility(android.view.View.GONE);
            btnHangup.setVisibility(android.view.View.VISIBLE);
        } else {
            tvStatus.setText("Входящий звонок");
            btnAccept.setVisibility(android.view.View.VISIBLE);
            btnReject.setVisibility(android.view.View.VISIBLE);
            btnHangup.setVisibility(android.view.View.GONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLogic();
                if (getIntent().getBooleanExtra("auto_accept", false) && !outgoing) accept();
            } else {
                Toast.makeText(this, "Нужен микрофон", Toast.LENGTH_LONG).show();
                intentionalEnd = true;
                finish();
            }
        }
    }

    private void startLogic() {
        if (started) return;
        started = true;
        try {
            rtc = new WebRtcClient(this, this);
            rtc.setListener(new WebRtcClient.Listener() {
                @Override public void onIceConnected() {
                    main.post(() -> {
                        connectedMedia = true;
                        if (tvStatus != null && inCall) tvStatus.setText("Разговор");
                    });
                }
                @Override public void onIceFailed() {
                    main.post(() -> Toast.makeText(CallActivity.this, "Нет медиа-соединения (ICE)", Toast.LENGTH_LONG).show());
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "WebRTC init", t);
            Toast.makeText(this, "WebRTC недоступен (проверь dependency)", Toast.LENGTH_LONG).show();
            intentionalEnd = true;
            finish();
            return;
        }
        // Audio focus handled inside WebRtcClient (JavaAudioDeviceModule)
        setupAudio(true);
        if (outgoing) {
            // Call signaling first; join voice only after accept (or immediately so peers list works)
            SocketManager.get().voiceCall(partner);
            // Join early so callee can see us after accept — web does join on accept only for callee;
            // caller joins on call_accepted. Matching web:
            // do not voiceJoin until accepted for cleaner pending_calls.
        }
    }

    private void setupAudio(boolean on) {
        if (audioManager == null) return;
        try {
            if (on) {
                prevAudioMode = audioManager.getMode();
                prevSpeaker = audioManager.isSpeakerphoneOn();
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
                audioManager.setMicrophoneMute(false);
            } else {
                audioManager.setMode(prevAudioMode);
                audioManager.setSpeakerphoneOn(prevSpeaker);
            }
        } catch (Exception e) {
            Log.e(TAG, "audio", e);
        }
    }

    private void accept() {
        if (outgoing || inCall) return;
        NotificationHelper.cancelIncomingCall(this);
        SocketManager.get().voiceCallAccept(partner);
        joinVoiceOnce();
        enterActive();
        // Callee waits for offer; if we already have partnerSid from incoming, still wait for offer
    }

    private void reject() {
        intentionalEnd = true;
        NotificationHelper.cancelIncomingCall(this);
        SocketManager.get().voiceCallReject(partner);
        cleanupLocalOnly();
        finish();
    }

    /** User pressed hangup */
    private void endCallByUser() {
        intentionalEnd = true;
        NotificationHelper.cancelIncomingCall(this);
        if (outgoing && !inCall) {
            SocketManager.get().voiceCallCancel(partner);
        }
        if (joinedVoice) {
            SocketManager.get().voiceLeave();
            joinedVoice = false;
        }
        cleanupLocalOnly();
        finish();
    }

    private void enterActive() {
        inCall = true;
        updateChrome();
        seconds = 0;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private void joinVoiceOnce() {
        if (joinedVoice) return;
        joinedVoice = true;
        SocketManager.get().voiceJoin(null);
    }

    private void tryConnectAsCaller() {
        if (!outgoing || rtc == null || offerSent) return;
        if (partnerSid == null || partnerSid.isEmpty()) return;
        offerSent = true;
        Log.i(TAG, "create offer -> " + partnerSid);
        rtc.connectAsCaller(partnerSid);
    }

    // --- Socket events ---

    @Override
    public void onCallAccepted(String from, String fromSid, String type) {
        if (partner == null || !partner.equals(from)) return;
        Log.i(TAG, "call_accepted from=" + from + " sid=" + fromSid);
        if (fromSid != null && !fromSid.isEmpty()) partnerSid = fromSid;
        enterActive();
        joinVoiceOnce();
        // Caller creates offer once peer is in voice room (also via voice_peers / joined)
        tryConnectAsCaller();
    }

    @Override
    public void onCallRejected(String from, String type) {
        if (partner == null || !partner.equals(from)) return;
        if (intentionalEnd) return;
        // Ignore spurious reject after we're already talking
        if (inCall && connectedMedia) return;
        Toast.makeText(this, "Отклонён", Toast.LENGTH_SHORT).show();
        intentionalEnd = true;
        if (joinedVoice) {
            SocketManager.get().voiceLeave();
            joinedVoice = false;
        }
        cleanupLocalOnly();
        finish();
    }

    @Override
    public void onCallCancelled(String from, String type) {
        if (partner == null || !partner.equals(from)) return;
        if (intentionalEnd) return;
        Toast.makeText(this, "Звонок отменён", Toast.LENGTH_SHORT).show();
        NotificationHelper.cancelIncomingCall(this);
        intentionalEnd = true;
        if (joinedVoice) {
            SocketManager.get().voiceLeave();
            joinedVoice = false;
        }
        cleanupLocalOnly();
        finish();
    }

    @Override
    public void onCallFailed(String reason, String target) {
        if (intentionalEnd) return;
        String msg = "offline".equals(reason) ? "Собеседник не в сети"
                : "banned".equals(reason) ? "Недоступно" : "Не удалось позвонить";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        intentionalEnd = true;
        cleanupLocalOnly();
        finish();
    }

    @Override
    public void onVoicePeers(Integer groupId, List<SocketManager.VoicePeer> peers) {
        if (groupId != null) return;
        if (!inCall || peers == null) return;
        for (SocketManager.VoicePeer p : peers) {
            if (partner != null && partner.equals(p.username) && p.sid != null && !p.sid.isEmpty()) {
                partnerSid = p.sid;
                tryConnectAsCaller();
                break;
            }
        }
    }

    @Override
    public void onVoiceUserJoined(Integer groupId, String username, String sid) {
        if (groupId != null) return;
        if (partner != null && partner.equals(username) && sid != null) {
            partnerSid = sid;
            tryConnectAsCaller();
        }
    }

    @Override
    public void onVoiceUserLeft(Integer groupId, String username, String sid) {
        if (groupId != null) return;
        if (intentionalEnd) return;
        if (partner != null && partner.equals(username) && inCall) {
            Toast.makeText(this, username + " завершил звонок", Toast.LENGTH_SHORT).show();
            intentionalEnd = true;
            joinedVoice = false; // peer left; don't leave again or already left
            cleanupLocalOnly();
            finish();
        }
    }

    @Override
    public void onVoiceOffer(String fromSid, String fromUser, JSONObject sdp, Integer groupId) {
        if (groupId != null) return;
        if (rtc == null || fromSid == null) return;
        // Only handle if from partner or we don't filter yet
        if (fromUser != null && partner != null && !fromUser.isEmpty() && !fromUser.equals(partner)) return;
        partnerSid = fromSid;
        if (!inCall) enterActive();
        Log.i(TAG, "got offer from " + fromSid);
        rtc.handleOffer(fromSid, sdp);
        connectedMedia = true;
        if (tvStatus != null) tvStatus.setText("Разговор");
    }

    @Override
    public void onVoiceAnswer(String fromSid, String fromUser, JSONObject sdp, Integer groupId) {
        if (groupId != null) return;
        if (rtc == null) return;
        Log.i(TAG, "got answer from " + fromSid);
        rtc.handleAnswer(fromSid, sdp);
        connectedMedia = true;
        if (tvStatus != null) tvStatus.setText("Разговор");
    }

    @Override
    public void onVoiceIce(String fromSid, String fromUser, JSONObject candidate, Integer groupId) {
        if (groupId != null) return;
        if (rtc != null) rtc.handleIce(fromSid, candidate);
    }

    @Override public void onConnected() {}
    @Override public void onDisconnected() {
        // Do NOT end call on transient socket blip — wait for reconnect
        Log.w(TAG, "socket disconnected during call");
    }
    @Override public void onGroupMessage(ru.florestdev.messenger.model.Message msg) {}
    @Override public void onPrivateMessage(ru.florestdev.messenger.model.Message msg) {}
    @Override public void onSystem(String text) {}

    @Override
    public void sendOffer(String toSid, JSONObject sdp) {
        SocketManager.get().voiceOffer(toSid, sdp, null);
    }

    @Override
    public void sendAnswer(String toSid, JSONObject sdp) {
        SocketManager.get().voiceAnswer(toSid, sdp, null);
    }

    @Override
    public void sendIce(String toSid, JSONObject candidate) {
        SocketManager.get().voiceIce(toSid, candidate, null);
    }

    private void cleanupLocalOnly() {
        main.removeCallbacks(tick);
        setupAudio(false);
        if (rtc != null) {
            try { rtc.dispose(); } catch (Exception ignored) {}
            rtc = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        SocketManager.get().removeListener(this);
        main.removeCallbacks(tick);
        // CRITICAL: do not voiceLeave / cancel here — only on intentionalEnd path
        if (intentionalEnd) {
            // already cleaned in endCallByUser / reject
        } else {
            // Activity dying unexpectedly (system) — only free local WebRTC, keep signaling
            // so peer is not told "call ended". Best-effort leave only if process is dying.
            if (rtc != null) {
                try { rtc.dispose(); } catch (Exception ignored) {}
                rtc = null;
            }
            setupAudio(false);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        endCallByUser();
    }
}
