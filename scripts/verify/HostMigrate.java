// 무중 검증 명령 host 모드 마이그레이션 (U1 3.5 Step 49 · J133 · J116 · BR8.1 · BR8.2 · SD-13)
//
// Docker를 쓸 수 없는 host 모드(사람 결정 11)에서 migrate 이미지(infra/migrate) 대신 같은 일을 한다:
// 소유자 역할 mujoong_owner로 core → realtime 위치를 차례로 적용한다. 설정은 migrate 이미지와 같은 파일
// (infra/migrate/flyway-core.conf · flyway-realtime.conf)을 읽고, 이미지 안 경로(filesystem:/flyway/sql/<위치>)만 저장소의
// 마이그레이션 디렉터리로 바꾼다. Flyway 판은 core-api bootJar에 든 flyway-core(= migrate 이미지의 Flyway CLI 판)다.
//
// 실행(scripts/verify/b-host.sh가 부른다, JDK 21 단일 파일 실행):
//   java -cp '<bootJar의 BOOT-INF/lib 압축 해제 디렉터리>/*' scripts/verify/HostMigrate.java \
//        <flyway-core.conf> <core SQL 디렉터리> <flyway-realtime.conf> <realtime SQL 디렉터리>
// 접속 정보는 환경 변수로만 받는다(명령줄 인자 · 파일에 비밀번호를 쓰지 않는다):
//   FLYWAY_URL(jdbc:postgresql://127.0.0.1:<포트>/mujoong) · FLYWAY_USER(mujoong_owner) · FLYWAY_PASSWORD
// 출력: 위치마다 적용한 마이그레이션 수와 최종 버전 한 줄. 비밀번호 · 접속 문자열 전체는 출력하지 않는다.
// 종료 코드: 0 성공, 1 마이그레이션 실패, 64 인자 · 환경 오류

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

public final class HostMigrate {
    private HostMigrate() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args.length % 2 != 0) {
            System.err.println("HostMigrate: 인자는 <설정 파일> <SQL 디렉터리> 쌍이다");
            System.exit(64);
        }
        String url = requiredEnv("FLYWAY_URL");
        String user = requiredEnv("FLYWAY_USER");
        String password = requiredEnv("FLYWAY_PASSWORD");
        for (int i = 0; i < args.length; i += 2) {
            Path conf = Path.of(args[i]);
            Path sqlDir = Path.of(args[i + 1]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(conf) || !Files.isDirectory(sqlDir)) {
                System.err.println("HostMigrate: 설정 파일 또는 SQL 디렉터리가 없다: " + conf + " · " + sqlDir);
                System.exit(64);
            }
            try {
                migrate(conf, sqlDir, url, user, password);
            } catch (RuntimeException e) {
                // 예외 메시지에는 SQL 문장 · 오류 위치가 들어갈 수 있지만 비밀번호는 들어가지 않는다(접속 정보는 환경 변수)
                System.err.println("HostMigrate: " + conf.getFileName() + " 적용 실패: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                System.exit(1);
            }
        }
    }

    private static void migrate(Path conf, Path sqlDir, String url, String user, String password) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(conf, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("설정 파일을 읽지 못했다: " + conf, e);
        }
        String location = properties.getProperty("flyway.locations", "");
        if (!location.startsWith("filesystem:/flyway/sql/")) {
            throw new IllegalStateException("예상한 이미지 경로(filesystem:/flyway/sql/<위치>)가 아니다: " + location);
        }
        // 이미지 안 경로만 저장소 디렉터리로 바꾸고 나머지(이력 표 · 스키마 · 기준선 · clean 금지 · 이름 규칙)는 그대로 쓴다
        properties.setProperty("flyway.locations", "filesystem:" + sqlDir);
        Flyway flyway = Flyway.configure()
                .configuration(properties)
                .dataSource(url, user, password)
                .load();
        MigrateResult result = flyway.migrate();
        if (!result.success) {
            throw new IllegalStateException("Flyway migrate가 success=false를 냈다");
        }
        System.out.println("HostMigrate: " + conf.getFileName() + " 적용 " + result.migrationsExecuted + "건, 최종 버전 "
                + (result.targetSchemaVersion == null ? "(변화 없음)" : result.targetSchemaVersion));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            System.err.println("HostMigrate: 필수 환경 변수 " + name + "가 비었다");
            System.exit(64);
        }
        return value;
    }
}
