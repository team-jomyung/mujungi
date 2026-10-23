package kr.mujoong.realtime.dialog;

import java.util.List;

/**
 * 모델이 답을 미루는 말로 응답을 끝냈는지 보는 사전 규칙 (JIRA-270).
 *
 * <p>2026-10-23 12:50 실호출 세션에서 GPT-Live가 "잠깐 생각해볼게" · "정리해서 말해볼게" · "지금까지 생각한 걸로 먼저 얘기해볼게"처럼 곧 말하겠다는
 * 말만 하고 이어 답하지 않았다. 응답 전사의 <b>끝</b>이 이런 말이면 서버 안전망이 이어 말하기 지시를 한 번 보낼 수 있다. 한글 음절만 남겨(띄어쓰기 ·
 * 문장부호 · 영문 표지 무시) 끝맺음을 비교한다. 순수 함수이고 전사 원문을 남기지 않는다(R-F14).
 */
public final class DeferralPhrases {

    /** 한글 음절만 남긴 끝맺음(띄어쓰기 없음). 곧 말하겠다고 약속하고 멈추는 꼴만 둔다. */
    private static final List<String> ENDINGS = List.of(
            "생각해볼게", "생각좀해볼게", "생각해보고말해줄게", "생각해보고말할게", "생각중이야", "생각하는중이야",
            "정리해볼게", "정리해서말해볼게", "정리해서말해줄게", "정리해서말할게",
            "얘기해볼게", "이야기해볼게", "얘기해줄게", "이야기해줄게", "말해볼게", "말해줄게",
            "잠깐만", "잠시만", "잠깐만기다려", "잠깐만기다려줘", "기다려줘");

    /** 검사할 끝 부분 길이(한글 음절 수). 긴 응답의 앞부분은 보지 않는다. */
    private static final int TAIL = 24;

    private DeferralPhrases() {
    }

    /** 응답 전사가 미루는 말로 끝나는가(빈 글 · null은 거짓). */
    public static boolean endsWithDeferral(CharSequence transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return false;
        }
        StringBuilder hangul = new StringBuilder();
        for (int i = transcript.length() - 1; i >= 0 && hangul.length() < TAIL; i--) {
            char c = transcript.charAt(i);
            if (c >= '가' && c <= '힣') {
                hangul.append(c);
            }
        }
        String tail = hangul.reverse().toString();
        for (String ending : ENDINGS) {
            if (tail.endsWith(ending)) {
                return true;
            }
        }
        return false;
    }
}
