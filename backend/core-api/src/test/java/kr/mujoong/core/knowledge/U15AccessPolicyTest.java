package kr.mujoong.core.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.activity.ActivityContextRepository;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.record.ActivityRecordRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u15")
class U15AccessPolicyTest {
    final UUID guardian = UUID.randomUUID(), child = UUID.randomUUID(), activity = UUID.randomUUID();
    final ChildRepository children = mock(ChildRepository.class);
    final ConsentPolicyService consents = mock(ConsentPolicyService.class);
    final GuardianWriteLock lock = mock(GuardianWriteLock.class);
    final ActivityRecordRepository records = mock(ActivityRecordRepository.class);
    final ActivityContextRepository contexts = mock(ActivityContextRepository.class);
    final KnowledgeAccessPolicy policy = new KnowledgeAccessPolicy(children, consents, lock, records, contexts);
    U15AccessPolicyTest() {
        when(children.findById(child)).thenReturn(Optional.of(new Child(child, guardian, "합성아이", 8, "[]", Instant.EPOCH)));
        when(consents.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(true, null, null));
        when(lock.statusForUpdate(guardian)).thenReturn(Optional.of("ACTIVE"));
    }
    ActivityRecordRepository.StoredRecord record(UUID recordChild, String type) {
        return new ActivityRecordRepository.StoredRecord(activity, guardian, recordChild, type, "ENDED", null, true,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
                "[{\"text\":\"가나다라마\"}]", "[]", null, "hash", Instant.EPOCH);
    }
    @Test void acceptsOwnerWithCurrentConsent() {
        policy.requireAllowed(guardian, child);
        verify(lock).lockActive(guardian);
        verify(consents).check(guardian);
    }
    @Test void deniesOtherGuardian() {
        assertThatThrownBy(() -> policy.requireAllowed(UUID.randomUUID(), child)).isInstanceOf(IllegalStateException.class);
    }
    @Test void deniesMissingConsent() {
        when(consents.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false, null, null));
        assertThatThrownBy(() -> policy.requireAllowed(guardian, child)).isInstanceOf(IllegalStateException.class);
    }
    @Test void deniesMissingChild() {
        assertThatThrownBy(() -> policy.requireAllowed(guardian, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsWrongActivityChildAndKind() {
        when(records.findOwned(activity, guardian)).thenReturn(Optional.of(record(UUID.randomUUID(), "FREE_TALK")));
        assertThatThrownBy(() -> policy.source(guardian, child, activity)).isInstanceOf(IllegalStateException.class);
        when(records.findOwned(activity, guardian)).thenReturn(Optional.of(record(child, "PRACTICE")));
        assertThatThrownBy(() -> policy.source(guardian, child, activity)).isInstanceOf(IllegalStateException.class);
    }
    @Test void sourceUnescapesJsonAndIncludesServerBackgroundOnly() {
        when(records.findOwned(activity, guardian)).thenReturn(Optional.of(record(child, "FREE_TALK")));
        when(contexts.findBySession(activity)).thenReturn(Optional.of(new ActivityContextRepository.StoredContext(activity, "합성 배경", null, null, "합성 질문")));
        var source = policy.source(guardian, child, activity);
        assertThat(source.rawTexts()).containsExactly("가나다라마", "합성 배경", "합성 질문");
        assertThat(source.names()).containsExactly("합성아이");
        assertThat(source.toString()).doesNotContain("합성", "가나다");
    }
    @Test void deletionDoesNotRequireCurrentConsent() {
        when(consents.check(guardian)).thenReturn(new ConsentPolicyService.GateResult(false, null, null));
        policy.requireDeletionOwner(guardian, child);
        verify(lock).statusForUpdate(guardian);
        verifyNoInteractions(consents);
    }
}
