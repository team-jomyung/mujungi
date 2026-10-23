package kr.mujoong.realtime.dialog;

import java.util.Objects;
import java.util.regex.Pattern;

/** 세션 시작에 고정하는 앱 포함 정책이다. 본문은 로그 표현에서 제외한다. */
public record PolicyBundle(String promptVersion, String policyVersion, String sourceSha256,
        String appliedSha256, String instructions) {

    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

    public PolicyBundle {
        require(VERSION, promptVersion);
        require(VERSION, policyVersion);
        require(HASH, sourceSha256);
        require(HASH, appliedSha256);
        if (Objects.requireNonNull(instructions, "instructions").isBlank()) {
            throw new IllegalArgumentException("서비스 정책 본문이 비었다");
        }
    }

    private static void require(Pattern format, String value) {
        if (value == null || !format.matcher(value).matches()) {
            throw new IllegalArgumentException("서비스 정책 식별 형식이 올바르지 않다");
        }
    }

    @Override
    public String toString() {
        return "PolicyBundle[promptVersion=" + promptVersion + ", appliedSha256=" + appliedSha256 + "]";
    }
}
