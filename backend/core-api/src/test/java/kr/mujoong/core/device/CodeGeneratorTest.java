package kr.mujoong.core.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.random.RandomGenerator;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 코드 추첨 · 충돌 재추첨 · 5회 실패 503 (BR1.2 · 편차 (c)). */
@Tag("u1")
class CodeGeneratorTest {

    @Test
    void drawsEightCharactersFromAlphabetDeterministicallyWithFixedRandom() {
        CodeGenerator first = new CodeGenerator(new Random(42), 5);
        CodeGenerator second = new CodeGenerator(new Random(42), 5);

        RegistrationCode a = first.draw();
        RegistrationCode b = second.draw();

        assertThat(a).isEqualTo(b);
        assertThat(a.value()).hasSize(8).matches("[" + RegistrationCode.ALPHABET + "]{8}");
    }

    @Test
    void storesOnFirstFreeCode() {
        CodeGenerator generator = new CodeGenerator(new Random(7), 5);
        List<RegistrationCode> tried = new ArrayList<>();

        String stored = generator.issue(code -> {
            tried.add(code);
            return Optional.of(code.value());
        });

        assertThat(tried).hasSize(1);
        assertThat(stored).isEqualTo(tried.get(0).value());
    }

    @Test
    void redrawsAfterCollisionsAndSucceedsOnFifthAttempt() {
        CodeGenerator generator = new CodeGenerator(new Random(7), 5);
        List<RegistrationCode> tried = new ArrayList<>();

        RegistrationCode stored = generator.issue(code -> {
            tried.add(code);
            return tried.size() < 5 ? Optional.empty() : Optional.of(code);
        });

        assertThat(tried).hasSize(5);
        assertThat(stored).isEqualTo(tried.get(4));
    }

    @Test
    void fiveCollisionsGiveCodeIssueFailed503() {
        CodeGenerator generator = new CodeGenerator(new Random(7), 5);
        List<RegistrationCode> tried = new ArrayList<>();

        assertThatThrownBy(() -> generator.issue(code -> {
            tried.add(code);
            return Optional.empty();
        })).isInstanceOfSatisfying(CoreApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CODE_ISSUE_FAILED);
            assertThat(e.httpStatus()).isEqualTo(503);
        });
        assertThat(tried).hasSize(5);
    }

    @Test
    void sameDrawEveryTimeStillCountsAttempts() {
        RandomGenerator alwaysZero = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        CodeGenerator generator = new CodeGenerator(alwaysZero, 3);
        List<String> tried = new ArrayList<>();

        assertThatThrownBy(() -> generator.issue(code -> {
            tried.add(code.value());
            return Optional.empty();
        })).isInstanceOf(CoreApiException.class);
        assertThat(tried).containsExactly("22222222", "22222222", "22222222");
    }

    @Test
    void attemptsMustBePositive() {
        assertThatThrownBy(() -> new CodeGenerator(new Random(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CodeGenerator(new Random(1), 5).attempts()).isEqualTo(5);
    }
}
