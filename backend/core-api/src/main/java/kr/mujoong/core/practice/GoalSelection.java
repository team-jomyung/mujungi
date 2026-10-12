package kr.mujoong.core.practice;
import java.time.Instant;
import java.util.UUID;
/** Internal stored selection; parent responses explicitly project only presentation fields. */
public record GoalSelection(UUID id,UUID guardianId,UUID childId,UUID recommendationId,UUID sourceSessionId,
        String behaviorKey,int behaviorVersion,String displayName,boolean isFavorite,long sortOrder,
        String status,Instant selectedAt,Instant deletedAt,Instant restoreUntil) {
    public boolean active() { return "ACTIVE".equals(status); }
}
