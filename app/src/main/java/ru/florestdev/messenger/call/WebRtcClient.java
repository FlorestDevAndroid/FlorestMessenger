package ru.florestdev.messenger.call;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Audio-only WebRTC compatible with site (voice_offer / answer / ice).
 * Uses JavaAudioDeviceModule so mic + speaker actually work on Android.
 */
public class WebRtcClient {
    private static final String TAG = "WebRtcClient";
    private static boolean factoryInit;

    public interface Signaling {
        void sendOffer(String toSid, JSONObject sdp);
        void sendAnswer(String toSid, JSONObject sdp);
        void sendIce(String toSid, JSONObject candidate);
    }

    public interface Listener {
        default void onIceConnected() {}
        default void onIceFailed() {}
    }

    private final Context appCtx;
    private final Signaling signaling;
    private Listener listener;
    private PeerConnectionFactory factory;
    private AudioDeviceModule audioModule;
    private EglBase egl;
    private AudioTrack localAudio;
    private AudioSource audioSource;
    private final Map<String, PeerConnection> pcs = new HashMap<>();
    private final Map<String, List<IceCandidate>> pendingIce = new HashMap<>();
    private final Map<String, Boolean> remoteDescSet = new HashMap<>();
    private final List<PeerConnection.IceServer> iceServers = new ArrayList<>();
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int prevMode = AudioManager.MODE_NORMAL;
    private boolean prevSpeaker;

    public WebRtcClient(Context ctx, Signaling signaling) {
        this.appCtx = ctx.getApplicationContext();
        this.signaling = signaling;
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());
        audioManager = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        requestAudioFocus();
        ensureFactory();
        createLocalAudio();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        try {
            prevMode = audioManager.getMode();
            prevSpeaker = audioManager.isSpeakerphoneOn();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            audioManager.setMicrophoneMute(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(f -> {})
                        .build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(f -> {},
                        AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception e) {
            Log.e(TAG, "audio focus", e);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(f -> {});
            }
            audioManager.setMode(prevMode);
            audioManager.setSpeakerphoneOn(prevSpeaker);
        } catch (Exception ignored) {}
    }

    private void ensureFactory() {
        if (!factoryInit) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appCtx)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions());
            factoryInit = true;
        }
        // Critical for mic capture + remote playback on real devices
        audioModule = JavaAudioDeviceModule.builder(appCtx)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override public void onWebRtcAudioRecordInitError(String s) { Log.e(TAG, "rec init " + s); }
                    @Override public void onWebRtcAudioRecordStartError(
                            JavaAudioDeviceModule.AudioRecordStartErrorCode c, String s) { Log.e(TAG, "rec start " + s); }
                    @Override public void onWebRtcAudioRecordError(String s) { Log.e(TAG, "rec err " + s); }
                })
                .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback() {
                    @Override public void onWebRtcAudioTrackInitError(String s) { Log.e(TAG, "play init " + s); }
                    @Override public void onWebRtcAudioTrackStartError(
                            JavaAudioDeviceModule.AudioTrackStartErrorCode c, String s) { Log.e(TAG, "play start " + s); }
                    @Override public void onWebRtcAudioTrackError(String s) { Log.e(TAG, "play err " + s); }
                })
                .createAudioDeviceModule();

        egl = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
        // Keep audioModule reference; release in dispose() after factory.dispose()
    }

    private void createLocalAudio() {
        MediaConstraints ac = new MediaConstraints();
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioSource = factory.createAudioSource(ac);
        localAudio = factory.createAudioTrack("ARDAMSa0", audioSource);
        localAudio.setEnabled(true);
        Log.i(TAG, "local audio track enabled");
    }

    private PeerConnection createPc(String remoteSid) {
        if (pcs.containsKey(remoteSid)) return pcs.get(remoteSid);

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        cfg.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;

        PeerConnection pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {
                Log.i(TAG, "signaling " + remoteSid + " " + state);
            }

            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                Log.i(TAG, "iceConnection " + remoteSid + " " + state);
                if (state == PeerConnection.IceConnectionState.CONNECTED
                        || state == PeerConnection.IceConnectionState.COMPLETED) {
                    if (listener != null) listener.onIceConnected();
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    if (listener != null) listener.onIceFailed();
                }
            }

            @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                Log.i(TAG, "pc state " + remoteSid + " " + state);
            }

            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                Log.i(TAG, "gathering " + state);
            }

            @Override public void onIceCandidate(IceCandidate candidate) {
                try {
                    // Format compatible with browser RTCIceCandidate(data.candidate)
                    JSONObject c = new JSONObject();
                    c.put("candidate", candidate.sdp);
                    c.put("sdpMid", candidate.sdpMid != null ? candidate.sdpMid : "0");
                    c.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    signaling.sendIce(remoteSid, c);
                    Log.i(TAG, "local ICE -> " + remoteSid);
                } catch (Exception e) {
                    Log.e(TAG, "ice emit", e);
                }
            }

            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(MediaStream mediaStream) {
                // Plan B legacy
                if (mediaStream == null) return;
                for (AudioTrack t : mediaStream.audioTracks) {
                    t.setEnabled(true);
                    Log.i(TAG, "remote audio stream track enabled (addStream)");
                }
            }
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}

            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = receiver != null ? receiver.track() : null;
                if (track != null) {
                    track.setEnabled(true);
                    Log.i(TAG, "onAddTrack kind=" + track.kind() + " enabled");
                }
            }

            @Override public void onTrack(RtpTransceiver transceiver) {
                if (transceiver == null || transceiver.getReceiver() == null) return;
                MediaStreamTrack track = transceiver.getReceiver().track();
                if (track != null) {
                    track.setEnabled(true);
                    Log.i(TAG, "onTrack kind=" + track.kind() + " enabled");
                }
            }
        });

        if (pc == null) {
            Log.e(TAG, "createPeerConnection returned null");
            return null;
        }

        // UNIFIED_PLAN: addTrack is preferred
        if (localAudio != null) {
            pc.addTrack(localAudio, java.util.Collections.singletonList("ARDAMS"));
            Log.i(TAG, "local audio addTrack");
        }
        pcs.put(remoteSid, pc);
        pendingIce.putIfAbsent(remoteSid, new ArrayList<>());
        remoteDescSet.put(remoteSid, false);
        return pc;
    }

    private static MediaConstraints audioSdpConstraints() {
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        return mc;
    }

    public void connectAsCaller(String remoteSid) {
        PeerConnection pc = createPc(remoteSid);
        if (pc == null) return;
        pc.createOffer(new SdpAdapter() {
            @Override public void onCreateSuccess(SessionDescription sd) {
                pc.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() {
                        try {
                            JSONObject sdp = new JSONObject();
                            sdp.put("type", sd.type.canonicalForm());
                            sdp.put("sdp", sd.description);
                            signaling.sendOffer(remoteSid, sdp);
                            Log.i(TAG, "offer sent to " + remoteSid);
                        } catch (Exception e) {
                            Log.e(TAG, "offer send", e);
                        }
                    }
                }, sd);
            }
        }, audioSdpConstraints());
    }

    public void handleOffer(String fromSid, JSONObject sdpJson) {
        SessionDescription remote = parseSdp(sdpJson, "offer");
        if (remote == null) {
            Log.e(TAG, "bad offer sdp");
            return;
        }
        PeerConnection pc = createPc(fromSid);
        if (pc == null) return;
        PeerConnection pcF = pc;
        pc.setRemoteDescription(new SdpAdapter() {
            @Override public void onSetSuccess() {
                markRemoteSet(fromSid);
                pcF.createAnswer(new SdpAdapter() {
                    @Override public void onCreateSuccess(SessionDescription sd) {
                        pcF.setLocalDescription(new SdpAdapter() {
                            @Override public void onSetSuccess() {
                                try {
                                    JSONObject sdp = new JSONObject();
                                    sdp.put("type", sd.type.canonicalForm());
                                    sdp.put("sdp", sd.description);
                                    signaling.sendAnswer(fromSid, sdp);
                                    Log.i(TAG, "answer sent to " + fromSid);
                                } catch (Exception e) {
                                    Log.e(TAG, "answer send", e);
                                }
                            }
                        }, sd);
                    }
                }, audioSdpConstraints());
            }
        }, remote);
    }

    public void handleAnswer(String fromSid, JSONObject sdpJson) {
        PeerConnection pc = pcs.get(fromSid);
        if (pc == null) {
            Log.e(TAG, "answer but no pc for " + fromSid);
            return;
        }
        SessionDescription remote = parseSdp(sdpJson, "answer");
        if (remote == null) return;
        pc.setRemoteDescription(new SdpAdapter() {
            @Override public void onSetSuccess() {
                markRemoteSet(fromSid);
                Log.i(TAG, "remote answer set");
            }
        }, remote);
    }

    public void handleIce(String fromSid, JSONObject cand) {
        if (cand == null) return;
        IceCandidate ice = parseIce(cand);
        if (ice == null) {
            Log.e(TAG, "bad ice: " + cand);
            return;
        }
        PeerConnection pc = pcs.get(fromSid);
        if (pc == null || !Boolean.TRUE.equals(remoteDescSet.get(fromSid))) {
            pendingIce.putIfAbsent(fromSid, new ArrayList<>());
            pendingIce.get(fromSid).add(ice);
            Log.i(TAG, "queued ICE for " + fromSid);
            return;
        }
        try {
            pc.addIceCandidate(ice);
            Log.i(TAG, "added ICE for " + fromSid);
        } catch (Exception e) {
            Log.e(TAG, "addIce", e);
        }
    }

    private void markRemoteSet(String sid) {
        remoteDescSet.put(sid, true);
        PeerConnection pc = pcs.get(sid);
        List<IceCandidate> q = pendingIce.get(sid);
        if (pc != null && q != null) {
            for (IceCandidate c : q) {
                try { pc.addIceCandidate(c); } catch (Exception e) { Log.e(TAG, "flush ice", e); }
            }
            q.clear();
        }
    }

    private static SessionDescription parseSdp(JSONObject sdpJson, String defaultType) {
        if (sdpJson == null) return null;
        try {
            // browser may nest or send type/sdp at top level
            String type = sdpJson.optString("type", defaultType);
            String sdp = sdpJson.optString("sdp", null);
            if (sdp == null || sdp.isEmpty()) {
                // sometimes entire description is string
                return null;
            }
            return new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);
        } catch (Exception e) {
            Log.e(TAG, "parseSdp", e);
            return null;
        }
    }

    private static IceCandidate parseIce(JSONObject cand) {
        try {
            // Web: { candidate: "candidate:...", sdpMid, sdpMLineIndex }
            // or nested under "candidate" as object
            if (cand.has("candidate") && cand.opt("candidate") instanceof JSONObject) {
                cand = cand.getJSONObject("candidate");
            }
            String sdp = cand.optString("candidate", "");
            if (sdp.isEmpty()) return null;
            String mid = cand.optString("sdpMid", "0");
            int idx = cand.optInt("sdpMLineIndex", 0);
            return new IceCandidate(mid, idx, sdp);
        } catch (Exception e) {
            return null;
        }
    }

    public void dispose() {
        abandonAudioFocus();
        for (PeerConnection pc : pcs.values()) {
            try { pc.close(); } catch (Exception ignored) {}
        }
        pcs.clear();
        pendingIce.clear();
        remoteDescSet.clear();
        if (localAudio != null) {
            try { localAudio.dispose(); } catch (Exception ignored) {}
            localAudio = null;
        }
        if (audioSource != null) {
            try { audioSource.dispose(); } catch (Exception ignored) {}
            audioSource = null;
        }
        if (factory != null) {
            try { factory.dispose(); } catch (Exception ignored) {}
            factory = null;
        }
        if (audioModule != null) {
            try { audioModule.release(); } catch (Exception ignored) {}
            audioModule = null;
        }
        if (egl != null) {
            try { egl.release(); } catch (Exception ignored) {}
            egl = null;
        }
    }

    private static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e(TAG, "sdp create fail: " + s); }
        @Override public void onSetFailure(String s) { Log.e(TAG, "sdp set fail: " + s); }
    }
}
