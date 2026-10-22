package kr.mujoong.realtime.session;

import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 녹음 안내 다음 첫 실제 재생까지의 재시작 방지. 전사 원문을 별도 보관하지 않는다. */
final class GreetingHandoff {
    private static final Pattern RESTART = Pattern.compile(
            "^(?:음[,，]?\\s*)?(?:안녕(?:하세요)?(?:[!.,?~\\s]|$)|반가워|반갑습니다|나는\\s*(?:AI|에이아이|무중|무중이)|저는\\s*(?:AI|에이아이|무중|무중이))");
    private static final Pattern SELF_INTRO = Pattern.compile("(?:나는|저는)\\s*(?:AI|에이아이|무중|무중이)");
    private static final Pattern FINAL_QUESTION = Pattern.compile("([^.!?。！？]+[?？])\\s*$");
    private record Playback(long generation, UUID connection, Instant deadline, AtomicBoolean frameSent) { }
    private final ConcurrentHashMap<UUID, Playback> playbacks = new ConcurrentHashMap<>();
    private boolean pending;
    private boolean identityRequested;
    /** 아이의 마지막 말이 인사(안녕 · 반가워 …)였다: 그 인사에 답하는 짧은 인사말은 재시작이 아니다. 자기소개와 안내 문장 반복은 계속 막는다. */
    private boolean childGreeted;

    void arm() { pending = true; }

    void childInput(String text) {
        String normalized = normalize(text);
        identityRequested = normalized.contains("너누구") || normalized.contains("넌누구")
                || normalized.contains("이름이뭐") || normalized.contains("이름은뭐")
                || normalized.contains("누구야") || normalized.contains("자기소개해")
                || normalized.endsWith("인사해줘") || normalized.endsWith("인사해줄래")
                || normalized.endsWith("안녕이라고말해줘");
        childGreeted = normalized.contains("안녕") || normalized.contains("반가워") || normalized.contains("반갑")
                || normalized.contains("하이") || normalized.contains("헬로");
    }

    boolean blocks(String text, String intro, String question) {
        if (!pending || text == null) return false;
        String normalized = normalize(text);
        boolean restart = RESTART.matcher(text.strip()).find();
        // 아이가 인사했으면 인사말만으로는 막지 않는다(자기소개가 들어 있으면 여전히 막는다)
        boolean greetingReply = childGreeted && !SELF_INTRO.matcher(text).find();
        return (!identityRequested && restart && !greetingReply)
                || duplicate(normalized, intro) || duplicate(normalized, question);
    }

    private boolean duplicate(String candidate, String approved) {
        String normalized = normalize(approved);
        if (!normalized.isEmpty() && candidate.contains(normalized)) return true;
        var question = FINAL_QUESTION.matcher(approved == null ? "" : approved);
        return question.find() && candidate.contains(normalize(question.group(1)));
    }

    void sent(UUID playback, long generation, UUID connection, Instant deadline) {
        playbacks.entrySet().removeIf(entry -> entry.getValue().generation() != generation
                || !entry.getValue().connection().equals(connection));
        if (pending) playbacks.put(playback, new Playback(generation, connection, deadline, new AtomicBoolean()));
    }

    void frameSent(UUID playback) {
        Playback current = playbacks.get(playback);
        if (current != null) current.frameSent().set(true);
    }

    void failed(UUID playback) { playbacks.remove(playback); }

    void clear() {
        pending = false;
        identityRequested = false;
        childGreeted = false;
        playbacks.clear();
    }

    void completed(UUID playback, long generation, UUID connection, Instant at) {
        Playback current = playbacks.get(playback);
        if (current != null && current.generation() == generation && current.connection().equals(connection)
                && at.isBefore(current.deadline()) && current.frameSent().get()) {
            pending = false;
            playbacks.clear();
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\p{P}\\p{Z}\\s]", "");
    }
}
