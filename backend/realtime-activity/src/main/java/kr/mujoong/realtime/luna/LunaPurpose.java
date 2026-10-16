package kr.mujoong.realtime.luna;

/**
 * Realtime Luna 호출 목적 (U9 entities LunaPurpose · contracts/ai-io.md 10.3). realtime에는 진행 중 판정 하나뿐이다. core 목적은 core에
 * 따로 있다(코드 공유 없음, R-F5 · BR7.3).
 */
public enum LunaPurpose {
    /** 진행 중 판정 J1 ~ J6(U11). 재시도 없음(NONE), 시한은 {@code mujoong.timing.judgment.live-budget}. */
    JUDGMENT_LIVE("ai-judgment-output.schema.json", "ai-judgment-request.schema.json");

    private final String outputSchema;
    private final String requestSchema;

    LunaPurpose(String outputSchema, String requestSchema) {
        this.outputSchema = outputSchema;
        this.requestSchema = requestSchema;
    }

    /** 출력 스키마 파일 이름. */
    public String outputSchema() {
        return outputSchema;
    }

    /** 요청 스키마 파일 이름(보내기 전 입력 검증, BR2.4). */
    public String requestSchema() {
        return requestSchema;
    }
}
