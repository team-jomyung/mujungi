package kr.mujoong.realtime.guardrail;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 제브 {@code choice} 질문 하나 (선 형식 정정 FR1.3 · D3 · D4). 정책 리소스({@code guardrail/policy-v2.json})의 한국어 검사 질문과 라벨별 설명을
 * 어댑터가 요청의 {@code questions}에 싣기 좋게 묶은 값 객체다. 문안은 코드 상수가 아니라 정책 리소스에서 온다(FR4).
 *
 * <p>{@code criteria}는 삽입 순서를 지킨 '라벨 → 설명' 맵이다(7개 범주 + {@value #PASS}). 문자열 표현에는 문안 원문을 내지 않는다.
 *
 * @param instructions 한국어 검사 질문(비어 있지 않음)
 * @param criteria 라벨 → 설명(비어 있지 않음, 값도 비어 있지 않음, 제브 상한 255개 이하)
 */
public record JevQuestion(String instructions, Map<String, String> criteria) {

    /** 선택지 라벨 중 '해당 없음'. 이 라벨이 1순위이고 문턱 이상일 때만 자동 통과한다. */
    public static final String PASS = "PASS";
    /** 제브가 한 질문에 받는 선택지 최대 수(공식 문서). */
    public static final int MAX_CHOICES = 255;

    public JevQuestion {
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(criteria, "criteria");
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("검사 질문이 비어 있다");
        }
        if (criteria.isEmpty() || criteria.size() > MAX_CHOICES) {
            throw new IllegalArgumentException("선택지 수가 올바르지 않다");
        }
        for (Map.Entry<String, String> entry : criteria.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("선택지 라벨 · 설명이 비어 있다");
            }
        }
        criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
    }

    /** 선택지 라벨(삽입 순서). */
    public List<String> labels() {
        return List.copyOf(criteria.keySet());
    }

    @Override
    public String toString() {
        return "JevQuestion[choices=" + criteria.size() + "]";
    }
}
