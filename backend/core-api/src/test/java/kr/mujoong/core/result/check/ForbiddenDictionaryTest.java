package kr.mujoong.core.result.check;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.*;
@Tag("result-report")
class ForbiddenDictionaryTest {
    ForbiddenDictionary dictionary=new ForbiddenDictionary();
    @Test void catchesEveryCategory(){for(String text:List.of("진단이 필요","치료가 필요","점수는 높음","핵심 원인","학교에서도 그래요","성격이 소심해요","반드시 하세요"))assertThat(dictionary.allowed(text)).as(text).isFalse();}
    @Test void ignoresQuotedSpeechOnlyForGeneratedText(){assertThat(dictionary.allowed("\"치료가 필요해\"라고 말했어요")).isTrue();assertThat(dictionary.allowedLiteral("\"치료가 필요해\"")).isFalse();}
    @Test void normalizesCaseSpacesAndZeroWidth(){assertThat(dictionary.allowed("Ａ ｄ Ｈ ｄ 의심")).isFalse();assertThat(dictionary.allowed("진\u200b단")).isFalse();}
    @Test void fixedChipsTemplatesAndNoticeDoNotTrigger(){for(String text:List.of("바로 전달","도움 뒤 전달","다음 기회로","보류","미실시 · 활동 끝","자료 부족","부탁하기 연습을 했어요. 장면 2개 중 1개를 진행했어요.","3분 동안 이야기를 나눴어요.","AI와의 연습 장면에서 본 기록이며 실제 친구 관계에서도 같은지는 알 수 없어요.","이 리포트는 진단 결과가 아니에요. 전문가에게 보여 줄 참고 자료예요. 제한사항은 마지막 항목에 있어요."))assertThat(dictionary.allowed(text)).as(text).isTrue();}
    /** JIRA-267(FT-1023-c): AI 없는 리포트 2 · 5 · 6 · 10항목의 고정 문구(임시 문구 J118)는 1차 사전에 걸리지 않는다. 인용 부호 예외 없이 본다. */
    @Test void reportRuleSectionPhrasesDoNotTrigger(){assertThat(kr.mujoong.core.report.ReportRuleSections.FIXED_PHRASES).isNotEmpty();for(String text:kr.mujoong.core.report.ReportRuleSections.FIXED_PHRASES)assertThat(dictionary.allowedLiteral(text)).as(text).isTrue();}
    @Test void malformedDictionaryFailsClosed(){assertThatThrownBy(()->new ForbiddenDictionary("{\"entries\":[]}")).isInstanceOf(IllegalStateException.class);}
    @Test void fixedNoticeDoesNotHideAdditionalDiagnosis(){assertThat(dictionary.allowed("이 리포트는 진단 결과가 아니에요. 전문가에게 보여 줄 참고 자료예요. 제한사항은 마지막 항목에 있어요. 자폐입니다")).isFalse();}
}
