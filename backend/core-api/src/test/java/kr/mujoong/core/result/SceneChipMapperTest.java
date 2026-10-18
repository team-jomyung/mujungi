package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
@Tag("result-report")
class SceneChipMapperTest {
    @ParameterizedTest @CsvSource({"functional,IMMEDIATE,DONE_IMMEDIATE,바로 전달","functional,AFTER_HELP,DONE_AFTER_HELP,도움 뒤 전달","partial,,NEXT_TIME,다음 기회로","none,,NEXT_TIME,다음 기회로","uncertain,,ON_HOLD,보류",",,NOT_DONE,미실시 · 활동 끝"})
    void maps(String value,String timing,String chip,String text){assertThat(SceneChipMapper.chip(value,timing)).isEqualTo(chip);assertThat(SceneChipMapper.text(chip,"ACTIVITY_ENDED")).isEqualTo(text);}
}
