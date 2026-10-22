package kr.mujoong.realtime.practiceflow;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 장면 도입이 기회 문장으로 끝나는가. 설명을 끝까지 재생했으면 아이가 마지막으로 들은 말이 기회 문장이므로, OPPORTUNITY 진입에서 같은
 * 문장을 또 재생하지 않는다. 친구 첫 대사가 없는 옛 대본(생성 판 v5까지)에만 쓴다 — 판 v6 대본은 무중의 설명 → 친구 첫 대사 → 기회 문장
 * 순서로 따로 재생한다(contracts/ai-io.md 10.15).
 *
 * <p>판 v5까지의 core는 BASE 설명이 기회 문장을 '포함'하는 것만 강제하고 위치 · 낱말 경계는 보지 않았으며, VARIANT 설명에는 포함도 강제하지
 * 않았다. 그래서 장면 종류와 관계없이 설명의 마지막 낱말들이 기회 문장과 같을 때만 생략한다. 설명 가운데에 있거나
 * 간접 인용('놀자고 했어')으로만 나오거나 다른 낱말 안에 든 짧은 문장('사줘' 속 '줘')은 아이가 상대의 직접 발화를 마지막에 듣지 못하므로
 * 따로 재생한다.
 *
 * <p>설명을 잘라 재생하지 않는다. 잘린 문자열은 core가 승인한 원문이 아니어서, 원문 일치로 제브를 건너뛰는 근거를 넓히게 되기 때문이다.
 * 비교는 core {@code ScenarioContentPolicy.normalize}와 같은 문자 부류(NFKC · 글자와 숫자만 · 소문자)로 하고, 낱말 경계는 그 부류 밖의
 * 문자(공백 · 문장부호 · 따옴표)다.
 */
final class SceneIntro {

    private static final Pattern WORD_BREAK = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SceneIntro() {
    }

    /**
     * 설명이 (정규화 비교로) 기회 문장으로 끝나고 기회 문장의 첫 글자가 설명의 낱말 첫 글자와 맞는가. 어느 쪽이든 비거나 정규화 결과가 비면
     * 거짓(기회 문장을 따로 재생한다).
     */
    static boolean carriesOpportunity(String sceneDescription, String opportunitySentence) {
        if (sceneDescription == null || opportunitySentence == null) {
            return false;
        }
        String opportunity = normalize(opportunitySentence);
        if (opportunity.isEmpty()) {
            return false;
        }
        String[] words = WORD_BREAK.split(Normalizer.normalize(sceneDescription, Normalizer.Form.NFKC));
        StringBuilder tail = new StringBuilder();
        for (int i = words.length - 1; i >= 0; i--) {
            tail.insert(0, normalize(words[i]));
            if (tail.length() >= opportunity.length()) {
                // 길이가 처음 같아지거나 넘는 곳에서만 비교한다: 넘었다면 기회 문장이 낱말 가운데서 시작한다
                return tail.toString().equals(opportunity);
            }
        }
        return false;
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }
}
