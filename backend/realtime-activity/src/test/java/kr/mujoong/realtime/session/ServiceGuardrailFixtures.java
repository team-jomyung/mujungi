package kr.mujoong.realtime.session;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import kr.mujoong.realtime.testing.SessionHarness;

/** 합성 PCM과 검수 메타데이터 대역이다. 운영 승인/실제 말소리 청취의 증거가 아니다. */
public final class ServiceGuardrailFixtures {
    public static final UUID INTRO = UUID.fromString("11111111-1111-4111-8111-111111111116");
    public static final UUID STOP = UUID.fromString("11111111-1111-4111-8111-111111111118");
    public static final String STOP_TEXT = "이야기를 그만할까?";
    public static final String TEXT = "나는 AI야. 우리 말은 글로 남고 네 목소리도 저장돼. 보호자는 글을 보고 직접 공유할 수 있어. "
            + "말하기 싫은 건 안 해도 되고 그만둘 수 있어. 지금 이야기해 볼까?";
    public static final String CODE = SessionExecutor.INTRO_FREE_TALK;
    public static final String URL = "/media/approved/intro_free_talk.wav";
    public static final int FRAMES = 2_400;

    private ServiceGuardrailFixtures() { }

    public static byte[] wav(int frames) {
        ByteBuffer bytes = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 2)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(24_000).putInt(48_000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 2);
        while (bytes.hasRemaining()) bytes.putShort((short) 1000);
        return bytes.array();
    }

    public static NoticeAssetCatalog.Asset asset(NoticeAssetCatalog.Verification verification) {
        return new NoticeAssetCatalog.Asset(INTRO, CODE, URL, "approved-audio/synthetic.wav",
                NoticeAssetCatalog.sha256(TEXT.getBytes(StandardCharsets.UTF_8)), NoticeAssetCatalog.sha256(wav(FRAMES)),
                FRAMES, verification, "synthetic-review-only", "service-0.1", true);
    }

    public static NoticeAssetCatalog catalog() {
        return catalog(null);
    }

    public static NoticeAssetCatalog catalog(UUID closingVersion) {
        var intro = asset(NoticeAssetCatalog.Verification.SPOKEN_VERIFIED);
        var reprompt = new NoticeAssetCatalog.Asset(UUID.fromString("11111111-1111-4111-8111-111111111117"),
                SessionExecutor.RE_PROMPT, "/media/approved/re_prompt.wav", intro.resource(), intro.textSha256(),
                intro.audioSha256(), intro.frames(), intro.verification(), intro.reviewVersion(), intro.policyVersion(), true);
        var stop = new NoticeAssetCatalog.Asset(STOP, "STOP_CONFIRM", "/media/approved/stop_confirm.wav",
                intro.resource(), NoticeAssetCatalog.sha256(STOP_TEXT.getBytes(StandardCharsets.UTF_8)),
                intro.audioSha256(), intro.frames(), intro.verification(), intro.reviewVersion(), intro.policyVersion(), false);
        var assets = new java.util.ArrayList<>(List.of(intro, reprompt, stop));
        if (closingVersion != null) assets.add(new NoticeAssetCatalog.Asset(closingVersion, "CLOSING",
                "/media/approved/closing.wav", intro.resource(),
                NoticeAssetCatalog.sha256("알겠어. 안녕.".getBytes(StandardCharsets.UTF_8)), intro.audioSha256(), intro.frames(),
                intro.verification(), intro.reviewVersion(), intro.policyVersion(), false));
        return new NoticeAssetCatalog(assets, path -> wav(FRAMES));
    }

    public static SessionHarness harness() {
        SessionHarness harness = new SessionHarness();
        harness.autoCompleteNoticeForJoin = false;
        return harness;
    }

    public static void configure(SessionHarness harness) {
        harness.extraApproved.put(CODE, TEXT);
        harness.approvedVersions.put(CODE, INTRO);
        harness.extraApproved.put(SessionExecutor.RE_PROMPT, TEXT);
        harness.approvedVersions.put(SessionExecutor.RE_PROMPT, UUID.fromString("11111111-1111-4111-8111-111111111117"));
        harness.closingText = "알겠어. 안녕.";
        harness.noticeAssets = catalog(harness.closingVersion);
        harness.extraApproved.put("STOP_CONFIRM", STOP_TEXT);
        harness.approvedVersions.put("STOP_CONFIRM", STOP);
    }

    public static SessionExecutor.ApprovedItem item() { return new SessionExecutor.ApprovedItem(CODE, INTRO, TEXT); }
    public static List<kr.mujoong.contract.ws.message.StartSession.ApprovedAudio> audio() {
        return List.of(new kr.mujoong.contract.ws.message.StartSession.ApprovedAudio(CODE, INTRO, URL));
    }
}
