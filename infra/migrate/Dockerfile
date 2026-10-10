# 무중 migrate 이미지 — Flyway CLI + 두 앱의 마이그레이션 SQL (U1 3.5 Step 43 · cicd 2.3.1 · infra-spec 2.1 · N-7 · BR8.1 · BR8.2 · J116 · J134)
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
# - 접속 정보는 이미지에 없다. 실행 때 migrate.env의 DB_OWNER_PASSWORD(mujoong_owner)만 받는다(infra-spec 3.7).
# - core → realtime 순서로 적용한다(flyway-core.conf · flyway-realtime.conf, 이력 표 flyway.*_schema_history, clean 비활성).
# - 비루트 uid 10001로 돈다. 쓰는 곳은 tmpfs /tmp 뿐이다(Compose read_only).

ARG FLYWAY_IMAGE=flyway/flyway:12.4.0

FROM ${FLYWAY_IMAGE}
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.title="mujoong-migrate" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"
RUN groupadd --system --gid 10001 mujoong \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin mujoong
COPY --from=core_sql . /flyway/sql/core/
COPY --from=realtime_sql . /flyway/sql/realtime/
COPY flyway-core.conf flyway-realtime.conf /flyway/conf/
COPY --chmod=0755 migrate.sh /usr/local/bin/mujoong-migrate
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/mujoong-migrate"]
