package kr.mujoong.realtime.luna;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * env {@code OPENAI_API_KEY}가 비어 있지 않고 공백뿐이 아니면 참 — realtime (U9 BR1.1 · SD11 · Q2-B). {@link RealtimeLunaClient} 빈은 이
 * 조건일 때만 등록된다. 키 값은 판정에만 쓰고 보관하지 않는다.
 */
public class LunaKeyPresentCondition implements Condition {

    /** 환경에 쓸 수 있는 키가 있는가. */
    public static boolean isPresent(Environment environment) {
        String key = environment.getProperty(LunaTransport.API_KEY_ENV);
        return key != null && !key.isBlank();
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isPresent(context.getEnvironment());
    }
}
