# 무중 backup 이미지 — PostgreSQL 16 클라이언트 + supercronic + flock, 비루트 (U1 3.5 Step 43 · J128 · R4-12 · V-15 · infra-spec 2.1 backup · 4.4 · cicd 2.3.1 · J352)
#
# 빌드 맥락은 infra/backup/ 이다(맥락의 파일은 쓰지 않는다).
#
#   docker build -f infra/backup/Dockerfile \
#     --build-arg GIT_SHA=<40자 커밋> --build-arg MUJOONG_SOURCE_FINGERPRINT=sha256:<64자> infra/backup
#   (보통은 infra/ci/build-images.sh가 부른다)
#
# - 베이스는 DB와 같은 postgres:16.15 이미지다(pg_dump · pg_dumpall · pg_restore · psql 16, util-linux flock, bash, tzdata).
#   같은 판의 클라이언트로 덤프하고, 호스트에서는 postgres 서비스와 이미지 계층을 나눠 쓴다. 태그와 다이제스트를 함께 고정한다.
# - 스케줄러는 root가 필요 없는 supercronic이다(V-15). 배포 바이너리(v0.2.49, 마지막 판)는 Go 1.26.6으로 빌드돼 Go 표준 라이브러리
#   HIGH 취약점이 있고 더 새 판이 없다(이미지 점검 ③). 그래서 같은 판의 소스(태그 커밋 고정)를 고친 Go(1.27.2, 이미지 다이제스트 고정)로
#   빌드한다(J352). 의존 모듈은 go.sum으로 검증한다. 이 빌드 단계만 네트워크로 Go 모듈을 받는다: CI는 바로 받고, 샌드박스(R-03)는
#   build-images.sh --extra-ca가 주는 BuildKit 비밀(extra_ca · proxy_env, required=false)로 받는다. 값은 빌드 인자 · ENV · 실행 이미지에
#   남지 않는다(빌드 단계는 실행 이미지에 들어가지 않는다).
# - 베이스 이미지의 gosu는 쓰지 않는다(entrypoint를 supercronic으로 바꾸고 uid 999로 돈다). 낡은 Go로 빌드된 바이너리라 지운다(J352).
# - 백업 스크립트 · 스케줄 정의는 이미지에 넣지 않는다. 인프라 번들의 backup/(backup.sh · crontab)을 배포가 고정 경로
#   /opt/mujoong/backup에 렌더링하고 컨테이너가 읽기 전용으로 마운트한다(cicd 2.3.1 backup 스크립트 번들, R4-12).
# - 실행 사용자는 uid 999(postgres)이고 TZ=Asia/Seoul은 Compose environment가 준다(03:00 KST).
# - 상속된 VOLUME /var/lib/postgresql/data는 쓰지 않는다. Compose가 그 경로에 작은 tmpfs를 덮어 익명 볼륨이 생기지 않게 한다.

ARG PG_IMAGE=postgres:16.15@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d
ARG GO_IMAGE=golang:1.27.2-alpine3.23@sha256:9e45f0eb4a63ed37ad6e604950407558b7c738e34e015ae77a5d4ad369cde079

# 1단계: supercronic v0.2.49(github.com/aptible/supercronic 태그 v0.2.49 = 커밋 8e0a4a40…) 정적 바이너리
FROM ${GO_IMAGE} AS supercronic
ADD https://github.com/aptible/supercronic.git#8e0a4a40090de8a22942c9fa573e27885ce18311 /src
WORKDIR /src
RUN --mount=type=secret,id=extra_ca,required=false \
    --mount=type=secret,id=proxy_env,required=false \
    set -eu; \
    if [ -s /run/secrets/extra_ca ]; then export SSL_CERT_FILE=/run/secrets/extra_ca; fi; \
    if [ -s /run/secrets/proxy_env ]; then set -a; . /run/secrets/proxy_env; set +a; fi; \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOFLAGS=-mod=readonly GOTOOLCHAIN=local \
      go build -trimpath -ldflags="-X main.Version=v0.2.49" -o /out/supercronic .

# 2단계: 실행 이미지
FROM ${PG_IMAGE}
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.title="mujoong-backup" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"
COPY --from=supercronic --chmod=0755 /out/supercronic /usr/local/bin/supercronic
# 덤프 · 로그 디렉터리를 uid 999 소유로 만들어 둔다. demo는 호스트 경로(/var/lib/mujoong-backup · /var/log/mujoong)를 그 위에
# 바인드하고, 이미지 점검 ④처럼 이름 있는 볼륨을 붙이면 이 소유권이 볼륨에 복사돼 비루트로 쓸 수 있다(네트워크 없음).
RUN rm -f /usr/local/bin/gosu \
 && install -d -o 999 -g 999 -m 0700 /backups \
 && install -d -o 999 -g 999 -m 0750 /var/log/mujoong
USER 999:999
WORKDIR /tmp
ENTRYPOINT ["/usr/local/bin/supercronic"]
CMD ["-passthrough-logs", "/opt/mujoong/backup/crontab"]
