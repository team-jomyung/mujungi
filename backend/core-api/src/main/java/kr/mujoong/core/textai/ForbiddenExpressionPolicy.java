package kr.mujoong.core.textai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Independent conservative literal dictionary; this does not claim semantic safety coverage. */
@Component
public class ForbiddenExpressionPolicy {
    private final List<String> patterns;
    public ForbiddenExpressionPolicy() {
        try {
            String source = new ClassPathResource("textai/practice-core-forbidden-patterns.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
            patterns = source.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("//"))
                    .map(ForbiddenExpressionPolicy::normalize).toList();
            if (patterns.isEmpty()) { throw new IllegalStateException("Empty Text AI dictionary"); }
        } catch (IOException failure) {
            throw new IllegalStateException("Text AI dictionary unavailable");
        }
    }
    public boolean isAllowed(String text) {
        if (text == null) { return false; }
        String normalized = normalize(text);
        return !normalized.isEmpty() && patterns.stream().noneMatch(normalized::contains);
    }
    public void requireAllowed(String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new TextAiException(TextAiException.Reason.INVALID_OUTPUT);
            }
            if (!isAllowed(text)) { throw new TextAiException(TextAiException.Reason.FORBIDDEN_OUTPUT); }
        }
    }
    static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder value = new StringBuilder();
        normalized.codePoints().filter(point -> Character.isLetterOrDigit(point) || point == '%')
                .forEach(value::appendCodePoint);
        return value.toString();
    }
}
