# 무중 backup 이미지 — PostgreSQL 16 클라이언트 + supercronic + flock, 비루트 (U1 3.5 Step 43 · J128 · R4-12 · V-15 · infra-spec 2.1 backup · 4.4 · cicd 2.3.1)
#
# 빌드 맥락은 infra/backup/ 이다(맥락의 파일은 쓰지 않는다).
#
#   docker build -f infra/backup/Dockerfile \
#     --build-arg GIT_SHA=<40자 커밋> --build-arg MUJOONG_SOURCE_FINGERPRINT=sha256:<64자> infra/backup
#   (보통은 infra/ci/build-images.sh가 부른다)
#
# - 베이스는 DB와 같은 postgres:16.15 이미지다(pg_dump · pg_dumpall · pg_restore · psql 16, util-linux flock, bash, tzdata).
#   같은 판의 클라이언트로 덤프하고, 호스트에서는 postgres 서비스와 이미지 계층을 나눠 쓴다.
# - 스케줄러는 root가 필요 없는 supercronic이다(V-15). 바이너리는 BuildKit ADD가 받고 sha256으로 고정한다
#   (RUN 단계 네트워크 · 프록시 · CA가 필요 없다, R-03). 판을 바꾸면 sha256도 같이 바꾼다.
# - 백업 스크립트 · 스케줄 정의는 이미지에 넣지 않는다. 인프라 번들의 backup/(backup.sh · crontab)을 배포가 고정 경로
#   /opt/mujoong/backup에 렌더링하고 컨테이너가 읽기 전용으로 마운트한다(cicd 2.3.1 backup 스크립트 번들, R4-12).
# - 실행 사용자는 uid 999(postgres)이고 TZ=Asia/Seoul은 Compose environment가 준다(03:00 KST).
# - 상속된 VOLUME /var/lib/postgresql/data는 쓰지 않는다. Compose가 그 경로에 작은 tmpfs를 덮어 익명 볼륨이 생기지 않게 한다.

ARG PG_IMAGE=postgres:16.15

FROM ${PG_IMAGE}
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.title="mujoong-backup" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"
# supercronic v0.2.49 linux-amd64 (sha1 e63c11a9726b775a6a11801e81af4f3fb926aa68)
ADD --checksum=sha256:a53ae236602c7338aba3fbaff40bda6300eae3b9fedb8261eb06cfe3724430c1 --chmod=0755 \
    https://github.com/aptible/supercronic/releases/download/v0.2.49/supercronic-linux-amd64 /usr/local/bin/supercronic
USER 999:999
WORKDIR /tmp
ENTRYPOINT ["/usr/local/bin/supercronic"]
CMD ["-passthrough-logs", "/opt/mujoong/backup/crontab"]
