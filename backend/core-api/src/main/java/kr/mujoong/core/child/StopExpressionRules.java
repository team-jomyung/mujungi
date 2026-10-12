package kr.mujoong.core.child;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.activity.SessionPackageBuilder;
import kr.mujoong.core.common.error.CoreApiException;

/**
 * 종료 표현 규칙 (U2 ChildProfile · FR2.4 · AC2.3.5 · AC2.3.6 · R9 · U2-J11 · 편차 (U2-i)).
 *
 * <p>기본 표현 '무중아 그만할래'는 저장하지 않는 코드 상수(U1 BR6.5 · {@link SessionPackageBuilder#DEFAULT_STOP_EXPRESSION})이고 추가 표현은
 * 최대 5개다. 표현은 NFC로 맞추고 앞뒤 공백을 뺀 2 ~ 20자, 정규화(공백 전부 제거 · 소문자) 기준으로 서로 같거나 기본 표현과 같으면 안 된다.
 * 어긋나면 400 VALIDATION_FAILED(입력값을 예외에 담지 않는다).
 */
public final class StopExpressionRules {

    /** 기본 종료 표현(U1 코드 상수와 같은 값). */
    public static final String DEFAULT_EXPRESSION = SessionPackageBuilder.DEFAULT_STOP_EXPRESSION;
    /** 추가 표현 최대 개수. */
    public static final int MAX_ITEMS = 5;
    /** 글자 수 범위. */
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 20;

    private StopExpressionRules() {
    }

    /** 저장할 표현 한 개(화면 글 · 정규화 글). */
    public record Expression(String text, String normalized) {

        /** 표현 글은 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "Expression[***]";
        }
    }

    /** 목록을 검증한다(순서 = sortOrder 1 ~ n). */
    public static List<Expression> validate(List<String> texts) {
        if (texts == null || texts.size() > MAX_ITEMS) {
            throw invalid();
        }
        Set<String> seen = new HashSet<>();
        seen.add(normalize(DEFAULT_EXPRESSION));
        List<Expression> expressions = new ArrayList<>();
        for (String raw : texts) {
            String text = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
            int length = text.codePointCount(0, text.length());
            if (length < MIN_LENGTH || length > MAX_LENGTH) {
                throw invalid();
            }
            String normalized = normalize(text);
            if (normalized.isEmpty() || !seen.add(normalized)) {
                throw invalid();
            }
            expressions.add(new Expression(text, normalized));
        }
        return expressions;
    }

    /** 정규화: NFC → 공백 전부 제거 → 소문자(U2-J11). */
    public static String normalize(String text) {
        String nfc = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC);
        return nfc.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static CoreApiException invalid() {
        return CoreApiException.of(ErrorCode.VALIDATION_FAILED);
    }
}
