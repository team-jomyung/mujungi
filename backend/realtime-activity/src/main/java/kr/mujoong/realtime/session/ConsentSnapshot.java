package kr.mujoong.realtime.session;

/**
 * 세션 시작 때 고정하는 동의 스냅샷(세션 패키지 consentSnapshot, 계약 5절 · entities ActivitySession · BR6.5).
 *
 * <p>다섯 값이 모두 있어야 한다. {@code realtime_activity_session}에는 이 값을 JSON 그대로({@code consent_snapshot_json}) 두고
 * 파생 열 policy_version = policyVersion, data_collection_allowed = transcript_storage_allowed = voiceAndText를 함께 쓴다
 * (DB 스냅샷 CHECK가 일치를 강제한다). 시작 뒤 바뀌지 않는다.
 *
 * @param policyVersion 정책 버전 문자열(J114, 1 ~ {@value #MAX_POLICY_VERSION_LENGTH}자)
 * @param voiceAndText 음성 · 텍스트 수집 동의
 * @param guardianView 보호자 열람 동의
 * @param reportSharing 리포트 공유 동의
 * @param rawAudio 원음 저장 동의(U1은 원음을 저장하지 않는다, NFR12.3)
 */
public record ConsentSnapshot(
        String policyVersion, boolean voiceAndText, boolean guardianView, boolean reportSharing, boolean rawAudio) {

    /** V1 열 길이 {@code policy_version varchar(50)}. */
    public static final int MAX_POLICY_VERSION_LENGTH = 50;

    public ConsentSnapshot {
        if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > MAX_POLICY_VERSION_LENGTH) {
            throw new IllegalArgumentException("policyVersion은 1 ~ " + MAX_POLICY_VERSION_LENGTH + "자여야 한다");
        }
    }
}
