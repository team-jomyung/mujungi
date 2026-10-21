package kr.mujoong.realtime.practiceflow;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 장면 도입이 기회 문장을 이미 들려주는가. core 규칙상 BASE 장면 설명은 기회 문장을 포함하므로(contracts/ai-io.md 10.15), 설명을 끝까지
 * 재생했으면 OPPORTUNITY 진입에서 같은 문장을 또 재생하지 않는다.
 *
 * <p>설명을 잘라 재생하지 않는다. 잘린 문자열은 core가 승인한 원문이 아니어서, 원문 일치로 제브를 건너뛰는 근거를 넓히게 되기 때문이다.
 * 비교는 core {@code ScenarioContentPolicy.normalize}와 같은 문자 부류(NFKC · 글자와 숫자만 · 소문자)로 한다.
 */
final class SceneIntro {

    private SceneIntro() {
    }

    /** 설명이 기회 문장을 (정규화 비교로) 담는가. 어느 쪽이든 비거나 정규화 결과가 비면 거짓(지금처럼 기회 문장을 따로 재생한다). */
    static boolean carriesOpportunity(String sceneDescription, String opportunitySentence) {
        if (sceneDescription == null || opportunitySentence == null) {
            return false;
        }
        String opportunity = normalize(opportunitySentence);
        return !opportunity.isEmpty() && normalize(sceneDescription).contains(opportunity);
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }
}
