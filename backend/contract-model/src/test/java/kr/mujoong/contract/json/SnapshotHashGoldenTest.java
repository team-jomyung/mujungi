package kr.mujoong.contract.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SessionRecordCaptured.snapshotHash 골든 값 (계약 6절 · U4b-R11 · U4a-J22 · U4a 리뷰 R5).
 *
 * <p>snapshotHash = {@code "sha256:"} + 정규화 JSON(키 사전순 정렬 · 공백 없음 · 비ASCII는 UTF-8 그대로)의 SHA-256 소문자 16진수다. 보내는 쪽
 * (Realtime U4b)과 받는 쪽(Core U4a)이 같은 함수를 쓰더라도, 기대값을 그 함수로 만들면 정의가 바뀌어도 잡지 못한다. 그래서 기대값은
 * 코드 밖(계약 정의대로 손으로 만든 정규화 문자열과 그 해시)에 고정한다. null 값의 처리(키를 남길지 뺄지)는 U4b가 계약 6절에 적을 때 이
 * 시험에 골든 값을 하나 더한다.
 */
@Tag("u4a")
class SnapshotHashGoldenTest {

    private static final JsonCodec CODEC = JsonCodec.shared();

    /** 계약 정의대로 손으로 만든 정규화 JSON. */
    private static final String CANONICAL = "{\"audio\":[{\"endedAt\":\"2026-10-14T16:11:02Z\",\"recordingId\":\"r1\","
            + "\"speaker\":\"CHILD\",\"startedAt\":\"2026-10-14T16:11:00Z\",\"storagePath\":\"2026/10/r1.wav\"}],\"gaps\":[],"
            + "\"recordScope\":{\"from\":\"2026-10-14T16:10:00Z\",\"to\":\"2026-10-14T16:20:00Z\"},"
            + "\"utterances\":[{\"endedAt\":\"2026-10-14T16:10:07Z\",\"seq\":1,\"speaker\":\"AI\","
            + "\"startedAt\":\"2026-10-14T16:10:05Z\",\"text\":\"안녕, 무중이야.\",\"uncertain\":false},"
            + "{\"endedAt\":\"2026-10-14T16:11:02Z\",\"seq\":2,\"speaker\":\"CHILD\",\"startedAt\":\"2026-10-14T16:11:00Z\","
            + "\"text\":\"블록 쌓았어\",\"uncertain\":true}]}";

    /** {@code printf '%s' "$CANONICAL" | sha256sum} 으로 코드 밖에서 구한 값. */
    private static final String GOLDEN = "sha256:688d94eb3d24406d3bff8a8cdc6f2b149c2d9a9b09b8bddd1df85d56321ab99e";

    @Test
    void theRecordHashIsTheSha256OfTheKeySortedCompactJson() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("utterances", List.of(
                ordered("seq", 1, "speaker", "AI", "text", "안녕, 무중이야.", "uncertain", false,
                        "startedAt", "2026-10-14T16:10:05Z", "endedAt", "2026-10-14T16:10:07Z"),
                ordered("seq", 2, "speaker", "CHILD", "text", "블록 쌓았어", "uncertain", true,
                        "startedAt", "2026-10-14T16:11:00Z", "endedAt", "2026-10-14T16:11:02Z")));
        record.put("gaps", List.of());
        record.put("recordScope", ordered("to", "2026-10-14T16:20:00Z", "from", "2026-10-14T16:10:00Z"));
        record.put("audio", List.of(ordered("recordingId", "r1", "speaker", "CHILD", "storagePath", "2026/10/r1.wav",
                "startedAt", "2026-10-14T16:11:00Z", "endedAt", "2026-10-14T16:11:02Z")));

        assertThat(CODEC.canonicalize(CODEC.write(record))).as("정규화 JSON").isEqualTo(CANONICAL);
        assertThat("sha256:" + CODEC.sha256OfCanonical(CODEC.write(record))).as("snapshotHash").isEqualTo(GOLDEN);
    }

    /**
     * U4b(계약 6절 null 규칙): null 값은 키를 남기고 JSON null로 쓴다 — practice가 없으면 {@code "practice":null}, 열린 끊김 구간은
     * {@code "to":null}. DECLINED · ACTIVE 전 종료처럼 빈 기록도 다섯 키를 모두 둔다.
     */
    @Test
    @Tag("u4b")
    void nullValuesKeepTheirKeysAsJsonNullInTheHash() {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("from", "2026-10-14T16:12:00Z");
        gap.put("to", null);
        gap.put("kind", "DISCONNECT");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("utterances", List.of());
        record.put("gaps", List.of(gap));
        record.put("recordScope", ordered("from", "2026-10-14T16:10:00Z", "to", "2026-10-14T16:20:00Z"));
        record.put("practice", null);
        record.put("audio", List.of());

        assertThat(CODEC.canonicalize(CODEC.write(record))).isEqualTo(NULL_CANONICAL);
        assertThat("sha256:" + CODEC.sha256OfCanonical(CODEC.write(record))).isEqualTo(NULL_GOLDEN);
    }

    /** 계약 정의대로 손으로 만든 정규화 JSON(null 키 유지). */
    private static final String NULL_CANONICAL = "{\"audio\":[],\"gaps\":[{\"from\":\"2026-10-14T16:12:00Z\",\"kind\":"
            + "\"DISCONNECT\",\"to\":null}],\"practice\":null,\"recordScope\":{\"from\":\"2026-10-14T16:10:00Z\",\"to\":"
            + "\"2026-10-14T16:20:00Z\"},\"utterances\":[]}";
    /** {@code printf '%s' "$NULL_CANONICAL" | sha256sum}. */
    private static final String NULL_GOLDEN = "sha256:206346024dc521c4c50dbbce6960aadb7af2fb48fa013c69b79df6a1916afbb2";

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
