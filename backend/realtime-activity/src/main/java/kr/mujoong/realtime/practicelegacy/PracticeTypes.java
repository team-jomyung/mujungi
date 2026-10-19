package kr.mujoong.realtime.practicelegacy;

/** U5b 내부 값. 작업 상태를 외부 stage 계약으로 내보내지 않는다. */
public final class PracticeTypes {
    private PracticeTypes() {}
    public enum Stage { SCENARIO_INTRO, FIRST_RESPONSE, HELP_OFFER, CUE, AFTER_CUE_RESPONSE, NEW_SCENARIO_RESPONSE }
    public enum GoalStatus { PENDING, RUNNING, DONE, SKIPPED, NOT_REACHED }
    public enum AttemptType { ORIGINAL, NEW_SCENARIO }
    public enum AttemptState { PREPARING, ISSUED, EXPOSED, COMPLETED, PREPARATION_FAILED }
    public enum ResponseType { FIRST, AFTER_CUE, NEW_SCENARIO }
    public enum EvaluationState { PENDING, FINALIZED, TERMINATED_PENDING }
    public enum Label { functional, partial, none, uncertain, NOT_PERFORMED }
    public enum Consent { ACCEPTED, DECLINED, NO_RESPONSE }
    public enum DeliveryState { NOT_GIVEN, REQUESTED, STARTED, COMPLETED, FAILED }
    public enum PreparationFailure { GENERATION_FAILED, TIMEOUT, UNAVAILABLE, VALIDATION_FAILED }
}
