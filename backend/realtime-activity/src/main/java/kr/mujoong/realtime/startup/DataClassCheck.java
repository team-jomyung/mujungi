package kr.mujoong.realtime.startup;

import java.util.Locale;
import java.util.Objects;
import kr.mujoong.realtime.gate.SeedDictionary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * S2b 데이터 등급과 시드 사전 수준 대조 (U1 3.5 Step 26 · RD-12 S2b · SD-24 안전장치 2 · J124).
 *
 * <p>DB 연결 직후 {@code mujoong_db.data_class}(init이 {@code ALTER DATABASE}로 정한 값, 앱 역할은 바꿀 수 없다)를 읽는다. 값이
 * {@code synthetic}이 아니면(없거나 모르는 값 포함) {@code real}로 본다(fail-closed, J124). {@code real}인데 시드 사전 수준이 SEED이면
 * 실제 아동 데이터에 임시 사전을 쓰게 되므로 기동을 거부한다. 거부 줄에는 등급 이름과 사전 수준만 쓴다.
 */
@Component
public class DataClassCheck {

    /** DB 설정 이름. */
    public static final String SETTING = "mujoong_db.data_class";
    /** 합성 데이터 등급 값. */
    public static final String SYNTHETIC = "synthetic";

    // missing_ok = true: 설정이 없으면 NULL(아래에서 real로 본다)
    private static final String QUERY = "select current_setting('" + SETTING + "', true)";

    /** 데이터 등급. */
    public enum DataClass {
        SYNTHETIC,
        REAL
    }

    private final JdbcTemplate jdbc;
    private final RealtimeStartupChecks checks;

    public DataClassCheck(JdbcTemplate jdbc, RealtimeStartupChecks checks) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.checks = Objects.requireNonNull(checks, "checks");
    }

    /** 대조한다. 통과하면 읽은 등급을 돌려준다. */
    public DataClass verify() {
        String raw;
        try {
            raw = jdbc.queryForObject(QUERY, String.class);
        } catch (DataAccessException e) {
            throw reject(SETTING + "를 읽지 못했다");
        }
        DataClass dataClass = classify(raw);
        if (!isAllowed(dataClass, checks.dictionary())) {
            throw reject(SETTING + "=" + dataClass + "인데 시드 사전 수준이 " + checks.dictionary().level() + "다(SD-24)");
        }
        return dataClass;
    }

    /** 값 해석: synthetic만 합성이고 그 밖(없음 · 모르는 값)은 real(J124 fail-closed). */
    public static DataClass classify(String raw) {
        return raw != null && SYNTHETIC.equals(raw.trim().toLowerCase(Locale.ROOT)) ? DataClass.SYNTHETIC : DataClass.REAL;
    }

    /** real 데이터에 SEED 수준 사전을 쓰지 않는다. */
    public static boolean isAllowed(DataClass dataClass, SeedDictionary dictionary) {
        return dataClass == DataClass.SYNTHETIC || !dictionary.isSeedLevel();
    }

    private static RealtimeStartupChecks.StartupRejectedException reject(String reason) {
        StartupFailureLine.print("DataClassRejected", reason);
        return new RealtimeStartupChecks.StartupRejectedException(reason);
    }
}
