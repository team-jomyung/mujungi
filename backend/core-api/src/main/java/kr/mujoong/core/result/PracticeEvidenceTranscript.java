package kr.mujoong.core.result;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 결과 AI에만 쓰는 장면 구간 투영. 원본 저장 전사와 대화 기록 화면은 변경하지 않는다. */
public final class PracticeEvidenceTranscript {
    private PracticeEvidenceTranscript() {}

    public static List<Map<String, Object>> forAnalysis(Map<String, Object> practice,
            List<Map<String, Object>> transcript) {
        if (!PracticeRecordV2.present(practice)) return transcript;
        List<PracticeRecordV2.Scene> scenes = PracticeRecordV2.read(practice);
        boolean prepared = scenes.stream().anyMatch(scene -> scene.facts().contains("PREPRACTICE_PROVIDED")
                || scene.facts().contains("PREPRACTICE_ATTEMPTED"));
        // 이전 기록은 endedAt가 없어도 기존 해석을 유지한다. 새 기록에서만 경계 미확인을 보수적으로 제외한다.
        if (!prepared) return transcript;
        List<PracticeRecordV2.Scene> bounded = scenes.stream()
                .filter(scene -> scene.endedAt() != null && scene.endedAt().isAfter(scene.startedAt())).toList();
        return transcript.stream().filter(turn -> withinOneScene(turn, bounded)).toList();
    }

    private static boolean withinOneScene(Map<String, Object> turn, List<PracticeRecordV2.Scene> scenes) {
        Instant from = instant(turn.get("startedAt"));
        Instant to = instant(turn.get("endedAt"));
        if (from == null || to == null || !to.isAfter(from)) return false;
        int overlaps = 0;
        boolean contained = false;
        for (PracticeRecordV2.Scene scene : scenes) {
            if (from.isBefore(scene.endedAt()) && to.isAfter(scene.startedAt())) {
                overlaps++;
                contained = !from.isBefore(scene.startedAt()) && !to.isAfter(scene.endedAt());
            }
        }
        // 장면 시작/종료를 걸치거나 여러 장면에 걸친 발화는 어느 수행인지 확정할 수 없다.
        return overlaps == 1 && contained;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant at) return at;
        if (!(value instanceof String text)) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeException invalid) {
            return null;
        }
    }
}
