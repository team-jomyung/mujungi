# 무중 migrate 이미지 — Flyway CLI(최소 구성) + 두 앱의 마이그레이션 SQL (U1 3.5 Step 43 · cicd 2.3.1 · infra-spec 2.1 · N-7 · BR8.1 · BR8.2 · J116 · J134 · J351)
#
# 빌드 맥락은 infra/migrate/ 이고, 두 앱의 마이그레이션 위치는 BuildKit 이름 있는 맥락으로 받는다(저장소 전체를 맥락으로 보내지 않는다):
#
#   docker build -f infra/migrate/Dockerfile \
#     --build-context core_sql=backend/core-api/src/main/resources/db/migration/core \
#     --build-context realtime_sql=backend/realtime-activity/src/main/resources/db/migration/realtime \
#     --build-arg GIT_SHA=<40자 커밋> --build-arg MUJOONG_SOURCE_FINGERPRINT=sha256:<64자> infra/migrate
#   (보통은 infra/ci/build-images.sh가 부른다)
#
# - Flyway CLI 판은 백엔드 라이브러리 판(gradle/libs.versions.toml의 Flyway 12.4.0)과 같다.
# - 기반은 앱 이미지와 같은 JRE(eclipse-temurin, 다이제스트 고정 + 같은 OS 패치)다(J351). Flyway 배포판 이미지(flyway/flyway)는
#   쓰지 않는 DB 드라이버 · 클라우드 플러그인 · 낡은 OS를 함께 담아 고칠 판이 있는 HIGH 이상 취약점이 많았다(이미지 점검 ③).
#   그래서 PostgreSQL migrate · info · validate에 필요한 jar만 Maven Central에서 sha256으로 고정해 받는다(BuildKit ADD,
#   RUN 단계 네트워크 없음 R-03): CLI · core · PostgreSQL 모듈 · 명령(verb) 5종(migrate · info · validate와 그 의존 baseline ·
#   schemas) · nc-core · nc-callbacks · Jackson 3(앱과 같은 3.1.7) · jansi · PostgreSQL 드라이버(앱과 같은 42.7.13).
#   클래스패스는 배포판 실행 스크립트와 같은 진입 클래스(org.flywaydb.commandline.Main)를 migrate.sh가 직접 부른다.
# - 접속 정보는 이미지에 없다. 실행 때 migrate.env의 DB_OWNER_PASSWORD(mujoong_owner)만 받는다(infra-spec 3.7).
# - core → realtime 순서로 적용한다(flyway-core.conf · flyway-realtime.conf, 이력 표 flyway.*_schema_history, clean 비활성).
#   FLYWAY_URL · FLYWAY_USER · FLYWAY_PASSWORD 환경 변수 계약 · 기준선 · afterMigrate 콜백은 CLI가 그대로 읽는다.
# - 비루트 uid 10001로 돈다. 쓰는 곳은 tmpfs /tmp 뿐이다(Compose read_only).

ARG JRE_IMAGE=eclipse-temurin:21.0.12_8-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f

# 0단계: 기반 이미지 OS 보안 패치(J350). core-api · realtime-activity Dockerfile과 같은 값이다(셋을 함께 바꾼다)
FROM scratch AS os-patches
ADD --checksum=sha256:219f43b1cd836a4da550938db5fda93160d269d39a4edcf1f1ce698a470db797 \
    https://snapshot.ubuntu.com/ubuntu/20261011T000000Z/pool/main/o/openssl/libssl3t64_3.0.13-0ubuntu3.16_amd64.deb /debs/
ADD --checksum=sha256:675b84971ffd4467707008c25ef7520f90ea7c23ef27b7a76b0dccf1d7c4dc3f \
    https://snapshot.ubuntu.com/ubuntu/20261011T000000Z/pool/main/o/openssl/openssl_3.0.13-0ubuntu3.16_amd64.deb /debs/

# 1단계: Flyway CLI 최소 구성(Maven Central, sha256 고정, 읽기 전용 0644). 판을 바꾸면 sha256도 같이 바꾼다
FROM scratch AS flyway-lib
ADD --chmod=0644 --checksum=sha256:e55bdf9ba05afe875c62747515d4a6ca48577885b7567e99c9ee609921a28680 \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/12.4.0/flyway-commandline-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:c3f93df1f3e0c9d14652adb5ade97401f2ede503859ac693c2af5c06e2797799 \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-core/12.4.0/flyway-core-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:abc075a5c3cbf37dc64b28fec7e2398fb14f31109f7ab6271854738797407b85 \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-database-postgresql/12.4.0/flyway-database-postgresql-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:d90fa01b045b7e2d930f26172951bd6c822aeff88d0f542e01b7cbb7e28af2e0 \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-verb-migrate/12.4.0/flyway-verb-migrate-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:bc8b12b135ec7aa7ea6b49c96ff738f5bc0377430fcca9ffd928cb58cc23adc3 \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-verb-info/12.4.0/flyway-verb-info-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:69269a34fad6d4ebcb0de24c3fbea8c02383b84a23c53f213373571449679d3a \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-verb-validate/12.4.0/flyway-verb-validate-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:6a927ecf91d107147c4c4c89461a892e176753437a66bdbd02c9dfb825166ac5 \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-verb-baseline/12.4.0/flyway-verb-baseline-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:909201089ea1ed63851466fbf340e764e5a5bd3febfc90b4d27e76acc845142a \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-verb-schemas/12.4.0/flyway-verb-schemas-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:51cd9e95fc0fd885af822313ec385d9f429f1a444f9274aa45965ece156e5f2a \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-nc-core/12.4.0/flyway-nc-core-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:1c9e77cc61c29d830316c7e32153d4dee957a5dc7a4477e6e9e95102f3d45caf \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-nc-callbacks/12.4.0/flyway-nc-callbacks-12.4.0.jar /lib/
ADD --chmod=0644 --checksum=sha256:7608c367fd8a92666d28711519599543c180298259963184ce04d2947a8f71e7 \
    https://repo1.maven.org/maven2/tools/jackson/core/jackson-core/3.1.7/jackson-core-3.1.7.jar /lib/
ADD --chmod=0644 --checksum=sha256:48c781de87e2016e1f0d973748a27130f11643e7ac080173f40e2d9669d84be3 \
    https://repo1.maven.org/maven2/tools/jackson/core/jackson-databind/3.1.7/jackson-databind-3.1.7.jar /lib/
ADD --chmod=0644 --checksum=sha256:ad24a4f51c9a2dcb397dd1f89938fdf3c38ff48f61b85c4a4edd02fe7671fdf0 \
    https://repo1.maven.org/maven2/tools/jackson/dataformat/jackson-dataformat-toml/3.1.7/jackson-dataformat-toml-3.1.7.jar /lib/
ADD --chmod=0644 --checksum=sha256:53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b \
    https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar /lib/
ADD --chmod=0644 --checksum=sha256:109e64fc65767c7a1a3bd654709d76f107b0a3b39db32cbf11139e13a6f5229b \
    https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/1.18/jansi-1.18.jar /lib/
ADD --chmod=0644 --checksum=sha256:6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93 \
    https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar /lib/

# 2단계: 실행 이미지
FROM ${JRE_IMAGE}
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.title="mujoong-migrate" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"
RUN --mount=type=bind,from=os-patches,source=/debs,target=/tmp/os-patches \
    dpkg -i /tmp/os-patches/*.deb \
 && groupadd --system --gid 10001 mujoong \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin mujoong
COPY --from=flyway-lib /lib/ /flyway/lib/
COPY --from=core_sql . /flyway/sql/core/
COPY --from=realtime_sql . /flyway/sql/realtime/
COPY flyway-core.conf flyway-realtime.conf /flyway/conf/
COPY --chmod=0755 migrate.sh /usr/local/bin/mujoong-migrate
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/mujoong-migrate"]
