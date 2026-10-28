package kr.mujoong.core.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 API 응답 모양 (JIRA-280 · contracts/schemas/admin-*.schema.json). 시각은 ISO 8601 UTC 문자열, 미확인 값은 null이고 0은 확인된 0이다.
 * 금액은 USD 소수 문자열이다({@link UsageCost#money}).
 */
public final class AdminViews {

    /** 끝 분류 표시 순서와 문구. ENDED_UNCONFIRMED는 화면 상태가 완료지만 정상 종료와 따로 센다. */
    static final Map<String, String> END_CATEGORY_LABEL = Map.of(
            "ENDED", "정상 종료",
            "ENDED_UNCONFIRMED", "종료 확인 안 됨(완료로 집계)",
            "INTERRUPTED", "진행 중 중단",
            "DECLINED", "아이가 참여하지 않음",
            "NOT_STARTED", "시작 실패");
    static final List<String> END_CATEGORY_ORDER = List.of("ENDED", "ENDED_UNCONFIRMED", "INTERRUPTED", "DECLINED", "NOT_STARTED");

    private static final Map<String, String> END_REASON_LABEL = Map.of(
            "GUARDIAN_STOP", "보호자 종료",
            "CHILD_STOP", "아이 종료 표현",
            "SILENCE", "말이 없어 자동 종료",
            "DISCONNECT", "기기 연결 끊김",
            "CONSENT_WITHDRAWN", "동의 철회",
            "UNCONFIRMED", "종료 확인 안 됨");
    private static final Map<String, String> FAILURE_LABEL = Map.of(
            "DEVICE_NO_ACK", "기기 응답 없음",
            "SCENARIO_GEN_FAILED", "연습 준비 실패",
            "PROVIDER_UNAVAILABLE", "AI 연결 실패",
            "SERVER_RESTART", "서버 재시작");
    private static final Map<String, String> NOT_DONE_LABEL = Map.of(
            "TIME_OVER", "시간이 다 되어 하지 못함",
            "SCENARIO_FAILED", "장면 준비 실패",
            "ACTIVITY_ENDED", "활동이 먼저 끝남",
            "NO_RESPONSE", "응답 없음");

    private AdminViews() {
    }

    public record Identity(String displayName, String role) {
    }

    public record Page<T>(List<T> items, int page, int pageSize, int total) {
    }

    /**
     * 활동 한 줄.
     *
     * @param status 화면 상태(COMPLETED · INTERRUPTED · FAILED_TO_START)
     * @param endCategory 끝 분류(ENDED · ENDED_UNCONFIRMED · INTERRUPTED · DECLINED · NOT_STARTED)
     * @param endReason 끝난 이유 표시 문구
     */
    public record ActivitySummary(String id, String guardianId, String guardianName, String deviceId, String childId,
            String childName, String type, String status, String endCategory, String startedAt, String endedAt,
            Double durationSeconds, Integer childTurns, Integer aiTurns, String endReason) {

        @Override
        public String toString() {
            return "ActivitySummary[id=" + id + ", status=" + status + "]";
        }
    }

    public record TranscriptLine(long sequence, String speaker, String text, String at) {

        @Override
        public String toString() {
            return "TranscriptLine[sequence=" + sequence + ", speaker=" + speaker + "]";
        }
    }

    public record HelpRecord(String id, String kind, String consentAnswer, boolean exampleGiven) {
    }

    /**
     * 장면 결과. 횟수 · 도움 기록은 Realtime 장면 실행 기록이 없으면 null · 빈 목록이다.
     *
     * @param helpOffer OFFERED · REQUESTED · NOT_OFFERED · UNKNOWN({@link HelpProjection})
     */
    public record SceneResult(String id, String title, String kind, String judgment, String reason, String responseTiming,
            String abstainReason, String helpResponse, String helpOffer, String variantSkipReason, Integer retryCount,
            Integer reExplanationCount, List<HelpRecord> helpRecords) {
    }

    public record ActivityDetail(String id, String guardianId, String guardianName, String deviceId, String childId,
            String childName, String type, String status, String endCategory, String startedAt, String endedAt,
            Double durationSeconds, Integer childTurns, Integer aiTurns, String endReason, boolean transcriptAvailable,
            List<TranscriptLine> transcript, List<SceneResult> scenes) {

        @Override
        public String toString() {
            return "ActivityDetail[id=" + id + ", transcript=" + transcript.size() + "]";
        }
    }

    public record ChildEntry(String id, String name, int activityCount) {

        @Override
        public String toString() {
            return "ChildEntry[id=" + id + "]";
        }
    }

    public record DeviceEntry(String id, String name, List<ChildEntry> children) {
    }

    public record GuardianSummary(String id, String name, int activityCount, List<DeviceEntry> devices) {
    }

    public record Averages(Double all, Double freeTalk, Double practice) {
    }

    public record DailyCount(String date, int freeTalk, int practice) {
    }

    public record EndReasonCount(String code, String label, int count) {
    }

    public record Overview(int activityCount, int freeTalkCount, int practiceCount, int activeChildren, Integer childTurns,
            Integer aiTurns, Averages averageSeconds, List<DailyCount> daily, List<EndReasonCount> endReasons,
            List<ActivitySummary> recentActivities) {
    }

    /**
     * 도움 반응 집계(장면당 한 번). unanswered는 '제안했지만 반응 없음'인데 지금 기록으로 가를 수 없어 늘 null이고, 그런 장면은 unknown에 든다.
     */
    public record Acceptance(int accepted, int declined, int unclear, int requested, Integer unanswered, int notOffered,
            int unknown, Double rate) {
    }

    public record KindCount(String kind, int count) {
    }

    public record QualityRecord(ActivitySummary activity, List<SceneResult> scenes) {
    }

    public record Quality(int startedPractices, int helpExecutionCount, int retryCount, Acceptance acceptance,
            List<KindCount> helpTypes, int focusExamples, List<QualityRecord> records, int recordsTotal, int recordsLimit) {
    }

    public record CostPhase(String phase, String model, int calls, Double voiceSeconds, Long inputTokens, Long outputTokens,
            Long cachedInputTokens, Long cacheWriteTokens, Long reasoningTokens, String estimatedUsd, String status,
            String calculation) {
    }

    public record CostSummary(String id, String activityId, String childName, String type, String category, String purpose,
            String observedAt, String status, Double voiceSeconds, Long inputTokens, Long outputTokens, String estimatedUsd) {

        @Override
        public String toString() {
            return "CostSummary[id=" + id + ", category=" + category + "]";
        }
    }

    public record CostDetail(String id, String activityId, String childName, String type, String category, String purpose,
            String observedAt, String status, Double voiceSeconds, Long inputTokens, Long outputTokens, String estimatedUsd,
            List<CostPhase> phases, String scope) {

        @Override
        public String toString() {
            return "CostDetail[id=" + id + ", category=" + category + "]";
        }
    }

    public record CostTotals(String collectedUsd, String activityUsd, String failedStartUsd, String nonActivityUsd,
            String unlinkedUsd, int unknownCount) {
    }

    public record Costs(CostTotals totals, Page<CostSummary> activities, List<CostSummary> otherCosts, String scope) {
    }

    /** 저장된 이름이 없는 보호자의 표시 이름(id 앞 6자). */
    static String guardianName(UUID id) {
        return "보호자 " + shortId(id);
    }

    /** 저장된 이름이 없는 기기의 표시 이름(id 앞 6자). */
    static String deviceName(UUID id) {
        return "기기 " + shortId(id);
    }

    static String shortId(UUID id) {
        return id.toString().substring(0, 6);
    }

    /** 끝난 이유 문구. 연습 출력 실패는 end_reason이 DISCONNECT여도 연결 끊김이 아니다(FT-1023-e). */
    static String endReasonLabel(String endCategory, String endReason, String failureCode, String endDetail) {
        return switch (endCategory) {
            case "DECLINED" -> "아이가 참여하지 않음";
            case "ENDED_UNCONFIRMED" -> "종료 확인 안 됨";
            case "NOT_STARTED" -> failureCode == null ? "시작하지 못함" : FAILURE_LABEL.getOrDefault(failureCode, "시작하지 못함");
            default -> {
                if ("PRACTICE_OUTPUT_FAILED".equals(endDetail)) {
                    yield "음성 안내 준비 실패";
                }
                if ("PRACTICE_COMPLETED".equals(endDetail)) {
                    yield "연습 완료";
                }
                if (endReason == null && failureCode != null) {
                    yield "SERVER_RESTART".equals(failureCode) ? "서버 재시작으로 중단"
                            : FAILURE_LABEL.getOrDefault(failureCode, "알 수 없음");
                }
                yield endReason == null ? "알 수 없음" : END_REASON_LABEL.getOrDefault(endReason, "알 수 없음");
            }
        };
    }

    static String notDoneLabel(String reason) {
        return reason == null ? null : NOT_DONE_LABEL.getOrDefault(reason, reason);
    }
}
