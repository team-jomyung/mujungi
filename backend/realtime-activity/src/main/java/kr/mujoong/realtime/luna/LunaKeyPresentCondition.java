package kr.mujoong.realtime.luna;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * env {@code OPENAI_API_KEY}가 비어 있지 않고 공백뿐이 아니며 스위치 {@code mujoong.luna.enabled}가 참이면 참 — realtime (U9 BR1.1 · SD11 ·
 * Q2-B, U11 BR12.3 · U11-c). {@link RealtimeLunaClient} 빈은 이 조건일 때만 등록된다(core-api Luna는 키만으로 켜지고 이 클래스를 쓰지 않는다).
 * 키 값은 판정에만 쓰고 보관하지 않는다.
 */
public class LunaKeyPresentCondition implements Condition {

    /** 스위치 설정 키(기본 false, application.yml). */
    public static final String SWITCH_KEY = "mujoong.luna.enabled";

    /** 환경에 쓸 수 있는 키가 있는가. */
    public static boolean isPresent(Environment environment) {
        String key = environment.getProperty(LunaTransport.API_KEY_ENV);
        return key != null && !key.isBlank();
    }

    /** realtime Luna 스위치가 켜져 있는가(없으면 꺼짐). */
    public static boolean isSwitchOn(Environment environment) {
        return environment.getProperty(SWITCH_KEY, Boolean.class, Boolean.FALSE);
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isPresent(context.getEnvironment()) && isSwitchOn(context.getEnvironment());
    }
}
