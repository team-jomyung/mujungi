package kr.mujoong.realtime.practiceflow;

import kr.mujoong.realtime.config.RealtimeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 새 연습 흐름의 설정 빈 (U12 BR9). 시간 값은 {@code mujoong.timing.conversation.*}와 슬롯 안전 시계 키, 횟수는
 * {@code mujoong.practice-flow.*}에서 읽는다. 연습 확장 Factory에 라우터를 끼우는 연결은 레거시 연습 구성(
 * {@code practice.PracticeRuntimeConfiguration#practiceFactory})이 이 빈을 받아서 한다 — Factory 시그니처 · 빈 이름은 바꾸지 않는다(D1).
 */
@Configuration(proxyBeanMethods = false)
public class PracticeFlowConfiguration {

    @Bean
    PracticeFlowSettings practiceFlowSettings(RealtimeProperties properties) {
        return PracticeFlowSettings.from(properties);
    }
}
