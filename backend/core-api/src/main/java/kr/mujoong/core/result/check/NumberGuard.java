package kr.mujoong.core.result.check;
/** BR5.4: 생성 문장의 인용 밖 숫자는 시각과 장면 번호만 허용한다. 서버 집계 문장은 대상이 아니다. */
public final class NumberGuard {
    private NumberGuard(){}
    public static boolean allowed(String text){
        String rest=QuoteMatcher.outsideQuotes(text).replaceAll("(?<![0-9])(?:[01][0-9]|2[0-3]):[0-5][0-9](?![0-9])","")
                .replaceAll("장면\\s+[12](?![0-9])","");
        return rest.codePoints().noneMatch(Character::isDigit);
    }
}
