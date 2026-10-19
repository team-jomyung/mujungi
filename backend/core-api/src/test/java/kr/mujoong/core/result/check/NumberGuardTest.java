package kr.mujoong.core.result.check;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
@Tag("result-report")
class NumberGuardTest {
    @Test void allowsClock(){assertThat(NumberGuard.allowed("10:32에 말했어요")).isTrue();}
    @Test void allowsSceneNumber(){assertThat(NumberGuard.allowed("장면 2에서 말했어요")).isTrue();}
    @Test void allowsQuotedNumber(){assertThat(NumberGuard.allowed("\"100개 있어\"라고 말했어요")).isTrue();}
    @Test void rejectsScoreAndCount(){assertThat(NumberGuard.allowed("3번 말했어요")).isFalse();assertThat(NumberGuard.allowed("90% 전달했어요")).isFalse();}
    @Test void rejectsInvalidClockAndScene(){assertThat(NumberGuard.allowed("25:80에 말했어요")).isFalse();assertThat(NumberGuard.allowed("장면 20에서 말했어요")).isFalse();}
}
