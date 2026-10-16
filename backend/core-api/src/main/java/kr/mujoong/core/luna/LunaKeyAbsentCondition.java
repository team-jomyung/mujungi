package kr.mujoong.core.luna;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * env {@code OPENAI_API_KEY}가 없거나 공백뿐이면 참 (U9 BR1.2 · SD11). 기존 실패 기본 빈(TextAiProvider · ResultTextProvider)에 건다.
 */
public class LunaKeyAbsentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !LunaKeyPresentCondition.isPresent(context.getEnvironment());
    }
}
