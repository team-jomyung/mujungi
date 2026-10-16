package kr.mujoong.core.luna;

/**
 * Core Luna 호출 목적 (U9 entities LunaPurpose · contracts/ai-io.md 10.3). 목적이 재시도 분류 · 출력 스키마 파일 · 시도 시한 키를 정한다.
 * {@code JUDGMENT_LIVE}는 realtime 몫이라 여기에 없다.
 */
public enum LunaPurpose {
    /** 상황 생성(U10). 지금 기존 TextAiProvider 경로는 json_object로 부른다(BR4.2). */
    SCENARIO_GENERATION(RetryClass.REQUEST_BOUNDED, "ai-scenario-output.schema.json"),
    /** 자체 검토 J7(U10). */
    SCENARIO_REVIEW(RetryClass.REQUEST_BOUNDED, "ai-scenario-review-output.schema.json"),
    /** 새 씨앗(U15). */
    SEED_GENERATION(RetryClass.REQUEST_BOUNDED, "ai-seed-output.schema.json"),
    /** 결과 문장 · 결과 작업(U6 · U14). 출력 스키마 파일이 없어 ResultAiSchema가 바깥에서 검증한다. */
    RESULT_TEXT(RetryClass.JOB_ATTEMPT, null),
    /** 목표 후보(U14). */
    GOAL_CANDIDATES(RetryClass.JOB_ATTEMPT, "ai-goal-candidate-output.schema.json"),
    /** 기록용 재판정(U14). */
    REJUDGMENT(RetryClass.JOB_ATTEMPT, "ai-rejudgment-output.schema.json");

    /** 재시도 분류(ai-io.md 10.3, BR3.1 ~ BR3.3). */
    public enum RetryClass {
        /** 한 번만 부른다. */
        NONE,
        /** 일시 실패 기술 재시도 1회 + 스키마 불일치 재요청 1회, 호출자 마감 안. */
        REQUEST_BOUNDED,
        /** 호출 안 재시도 없음. 결과 작업 재시도가 다시 한다. */
        JOB_ATTEMPT
    }

    private final RetryClass retryClass;
    private final String outputSchema;

    LunaPurpose(RetryClass retryClass, String outputSchema) {
        this.retryClass = retryClass;
        this.outputSchema = outputSchema;
    }

    /** 재시도 분류. */
    public RetryClass retryClass() {
        return retryClass;
    }

    /** 출력 스키마 파일 이름(없으면 null, json_object만 요청). */
    public String outputSchema() {
        return outputSchema;
    }

    /** 결과류(결과 작업 시한 키 {@code mujoong.text-ai.result.request-timeout})인가. 그 밖은 {@code mujoong.text-ai.request-timeout}. */
    public boolean usesResultTimeout() {
        return retryClass == RetryClass.JOB_ATTEMPT;
    }
}
