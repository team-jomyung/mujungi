package kr.mujoong.contract.context;

import java.util.List;
import java.util.Objects;

/** 서버가 선택한 유한한 주제만 전달한다. 보호자 원문·질문·이름·연락처를 담을 문자열 필드가 없다. */
public record ConversationGuide(List<Topic> topics, SourceType sourceType) {
    public enum Topic { FRIEND, SCHOOL, FAMILY, ACADEMY, BLOCKS, DRAWING, SPORTS, DINOSAURS, PLAY, DAILY }
    public enum SourceType { SEEN, HEARD_FROM_CHILD, UNSPECIFIED }
    public ConversationGuide {
        topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
        Objects.requireNonNull(sourceType, "sourceType");
        if (topics.isEmpty() || topics.size() > 3 || topics.stream().distinct().count() != topics.size())
            throw new IllegalArgumentException("Invalid conversation guide");
    }
    @Override public String toString() { return "ConversationGuide[redacted]"; }
}
