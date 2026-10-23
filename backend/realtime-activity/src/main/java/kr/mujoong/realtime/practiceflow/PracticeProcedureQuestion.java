package kr.mujoong.realtime.practiceflow;

import java.util.regex.Pattern;

/** Narrow completion safeguard for explicit Korean questions about what to say. Not a replacement for the judge. */
final class PracticeProcedureQuestion {
    private static final Pattern HOW_TO_SAY = Pattern.compile(
            "(?:뭐라고|어떻게).{0,30}(?:말|대답|질문|물어).{0,20}(?:해야|하면|하라는|보라는|봐야).{0,12}(?:거야|돼|되나요|할까|까요|해|해요)[?？.!]*$");
    private static final Pattern SHOULD_SAY = Pattern.compile(
            "(?:말해야|대답해야|질문해야|물어봐야|물어보면|말하면|대답하면|질문하면|말하라는|물어보라는).{0,10}(?:하는거야|되는거야|되는거지|하라는거야|돼[?？]|되나요|할까[?？]|될까[?？])[?？.!]*$");

    private PracticeProcedureQuestion() { }

    static boolean matches(String text) {
        if (text == null) return false;
        String compact = text.replaceAll("\\s+", "");
        return HOW_TO_SAY.matcher(compact).find() || SHOULD_SAY.matcher(compact).find();
    }
}
