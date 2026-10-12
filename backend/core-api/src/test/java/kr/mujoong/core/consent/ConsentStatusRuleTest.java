package kr.mujoong.core.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.consent.ConsentStatusRule.Evaluation;
import kr.mujoong.core.consent.ConsentStatusRule.Status;
import kr.mujoong.core.consent.ConsentStatusRule.Type;
import kr.mujoong.core.identity.Consent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 동의 상태 판정(U2 · R6 · FR1.2 · 편차 (U2-g) · U2-J8 · U2-J10). */
@Tag("u2")
class ConsentStatusRuleTest {

    private static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");
    private static final UUID GUARDIAN = UUID.randomUUID();

    @Test
    void allFourItemsOfTheCurrentVersionAreGiven() {
        Evaluation evaluation = ConsentStatusRule.evaluate(allGiven("1.0", 1, T0), Optional.of("1.0"), T0);

        assertThat(evaluation.status()).isEqualTo(Status.GIVEN);
        assertThat(evaluation.ok()).isTrue();
        assertThat(evaluation.givenByType()).hasSize(4).allSatisfy((type, given) -> assertThat(given).isTrue());
    }

    @Test
    void noRowsOrSomeItemsOnlyAreNotGiven() {
        assertThat(ConsentStatusRule.evaluate(List.of(), Optional.of("1.0"), T0).status()).isEqualTo(Status.NOT_GIVEN);

        List<Consent> three = new ArrayList<>(allGiven("1.0", 1, T0));
        three.removeIf(row -> row.getConsentType().equals(Type.RAW_AUDIO.name()));
        Evaluation partial = ConsentStatusRule.evaluate(three, Optional.of("1.0"), T0);
        assertThat(partial.status()).isEqualTo(Status.NOT_GIVEN);
        assertThat(partial.givenByType()).containsEntry(Type.RAW_AUDIO, false).containsEntry(Type.GUARDIAN_VIEW, true);
    }

    @Test
    void onlyAnOlderPolicyVersionMeansOutdatedSoTheGuardianReconsents() {
        Evaluation evaluation = ConsentStatusRule.evaluate(allGiven("1.0", 1, T0), Optional.of("1.1"), T0);

        assertThat(evaluation.status()).isEqualTo(Status.OUTDATED);
        assertThat(evaluation.ok()).isFalse();

        List<Consent> both = new ArrayList<>(allGiven("1.0", 1, T0));
        both.addAll(allGiven("1.1", 2, T0.plusSeconds(1)));
        assertThat(ConsentStatusRule.evaluate(both, Optional.of("1.1"), T0.plusSeconds(1)).status()).isEqualTo(Status.GIVEN);
    }

    @Test
    void aLaterWithdrawnRowWinsOverTheEarlierGivenRow() {
        List<Consent> rows = new ArrayList<>(allGiven("1.0", 1, T0));
        UUID child = UUID.randomUUID();
        rows.add(new Consent(UUID.randomUUID(), GUARDIAN, child, Type.VOICE_AND_TEXT.name(), Consent.STATUS_WITHDRAWN, 2,
                "1.0", T0.plusSeconds(10)));

        assertThat(ConsentStatusRule.evaluate(rows, Optional.of("1.0"), T0.plusSeconds(5)).status()).isEqualTo(Status.GIVEN);
        assertThat(ConsentStatusRule.evaluate(rows, Optional.of("1.0"), T0.plusSeconds(10)).status())
                .as("철회 행이 효력을 가진 뒤").isEqualTo(Status.NOT_GIVEN);
    }

    @Test
    void rowsThatStartInTheFutureDoNotCountYet() {
        List<Consent> future = allGiven("1.0", 1, T0.plus(Duration.ofMinutes(1)));

        assertThat(ConsentStatusRule.evaluate(future, Optional.of("1.0"), T0).status()).isEqualTo(Status.NOT_GIVEN);
    }

    @Test
    void anUnknownPolicyVersionIsClosed() {
        assertThat(ConsentStatusRule.evaluate(allGiven("1.0", 1, T0), Optional.empty(), T0).status())
                .isEqualTo(Status.NOT_GIVEN);
        assertThat(ConsentStatusRule.evaluate(allGiven("1.0", 1, T0), Optional.of(" "), T0).status())
                .isEqualTo(Status.NOT_GIVEN);
        assertThatThrownBy(() -> ConsentStatusRule.evaluate(null, Optional.of("1.0"), T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void typesReadOnlyTheFourWireNames() {
        assertThat(Type.fromWire("RAW_AUDIO")).contains(Type.RAW_AUDIO);
        assertThat(Type.fromWire("MARKETING")).isEmpty();
        assertThat(Type.fromWire(null)).isEmpty();
    }

    @Test
    void consentRowsBeforeTheChildIsRegisteredHaveNoChild() {
        Consent beforeChild = new Consent(UUID.randomUUID(), GUARDIAN, null, Type.GUARDIAN_VIEW.name(), 1, "1.0",
                UUID.randomUUID(), T0);

        assertThat(beforeChild.getChildId()).isNull();
        assertThat(beforeChild.getTermsVersionId()).isNotNull();
        assertThat(beforeChild.isGivenAt(T0)).isTrue();
    }

    private static List<Consent> allGiven(String policyVersion, int consentVersion, Instant from) {
        List<Consent> rows = new ArrayList<>();
        for (Type type : Type.values()) {
            rows.add(new Consent(UUID.randomUUID(), GUARDIAN, null, type.name(), consentVersion, policyVersion, null, from));
        }
        return rows;
    }
}
