# 무중 realtime-activity 이미지 (U1 3.5 Step 43 · cicd 2.3.1 · 2.3.2 · infra-spec 2.1 · 2.2 · 2.5 · SD-16 · SD-22 · SD-25 · R-03 · J134)
#
# 빌드 맥락은 backend/ 이다(backend/.dockerignore가 bootJar 둘만 들인다). 이미지 안에서 Gradle을 돌리지 않고
# 호스트(검증 명령 A · CI backend 작업)가 만든 bootJar를 COPY한다(샌드박스 빌드 규칙 R-03, 컨테이너 안 네트워크 없음).
#
#   docker build -f backend/realtime-activity/Dockerfile \
#     --build-arg GIT_SHA=<40자 커밋> --build-arg MUJOONG_SOURCE_FINGERPRINT=sha256:<64자> backend
#   (보통은 infra/ci/build-images.sh가 부른다)
#
# - 비밀(공급자 키 포함) · 프록시 · CA 값을 ARG · ENV로 받지 않는다. JVM 옵션 · 프로필 · 비밀은 Compose가 준다(infra-spec 2.5 · 3.7).
# - 장애 주입 훅 · 가짜 공급자는 이 이미지에 없다(SD-25 · J129). 가짜 공급자는 tools/fake-provider 별도 이미지다.
# - 비루트 uid 10001, 최소 HTTP 클라이언트 curl(베이스 이미지에 있음)로 컨테이너 안 readiness를 부른다(infra-spec 2.3).
# - 라벨: 커밋 SHA(cicd 2.3.1) · 소스 지문(J134 (가), infra/ci/source-fingerprint.sh).
# - 기반 이미지는 태그와 다이제스트를 함께 고정한다(재현성, J350). 기반이 바뀌면 다이제스트와 0단계 OS 패치를 함께 다시 본다.

ARG JRE_IMAGE=eclipse-temurin:21.0.12_8-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f

# 0단계: 기반 이미지 OS 보안 패치(J350, 이미지 점검 ③). 같은 태그의 더 새 다이제스트가 아직 없어(Docker Hub 2026-09-18 판이
# openssl 3.0.13-0ubuntu3.15) 고친 판 .deb를 Ubuntu 스냅숏 주소(바뀌지 않는 주소)에서 sha256으로 고정해 받는다.
# BuildKit ADD가 받으므로 RUN 단계 네트워크가 없다(R-03). 실행 이미지에는 .deb가 남지 않는다(bind 마운트로만 설치).
# core-api · migrate Dockerfile과 같은 값이다(셋을 함께 바꾼다). 기반 다이제스트가 이 판 이상을 담으면 이 단계를 지운다.
FROM scratch AS os-patches
ADD --checksum=sha256:219f43b1cd836a4da550938db5fda93160d269d39a4edcf1f1ce698a470db797 \
    https://snapshot.ubuntu.com/ubuntu/20261011T000000Z/pool/main/o/openssl/libssl3t64_3.0.13-0ubuntu3.16_amd64.deb /debs/
ADD --checksum=sha256:675b84971ffd4467707008c25ef7520f90ea7c23ef27b7a76b0dccf1d7c4dc3f \
    https://snapshot.ubuntu.com/ubuntu/20261011T000000Z/pool/main/o/openssl/openssl_3.0.13-0ubuntu3.16_amd64.deb /debs/

# 1단계: bootJar를 계층별로 푼다
FROM ${JRE_IMAGE} AS layers
WORKDIR /layers
COPY realtime-activity/build/libs/realtime-activity.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted \
 && rm app.jar

# 2단계: 실행 이미지
FROM ${JRE_IMAGE}
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.title="mujoong-realtime" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"
RUN --mount=type=bind,from=os-patches,source=/debs,target=/tmp/os-patches \
    dpkg -i /tmp/os-patches/*.deb \
 && groupadd --system --gid 10001 mujoong \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin mujoong
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER 10001:10001
# 8081 서비스 포트(기기 WSS · 내부 API), 9090 관리 포트(컨테이너 루프백에만 묶인다, infra-spec N-1)
EXPOSE 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
