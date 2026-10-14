package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.mujoong.realtime.startup.SchemaVersionCheck;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("u5b")
class PracticeMigrationRegistrationTest {
    @Test
    void migrationIsVisibleToTheDeploymentAndStartupCheck() {
        assertThat(new ClassPathResource("db/migration/realtime/V4__u5b_practice.sql").exists()).isTrue();
        assertThat(SchemaVersionCheck.expectedVersion()).isEqualTo("4");
    }

    @Test
    void anAppWithPracticeEnabledRefusesAnUnmigratedV3Schema() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("0", "1", "2", "3"));
        assertThatThrownBy(() -> new SchemaVersionCheck(jdbc).verify())
                .hasMessageContaining("버전 3 < 기대 4");
    }
}
