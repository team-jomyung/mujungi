# 무중 web 이미지 — Next.js standalone (U1 3.5 Step 43 · cicd 2.3.1 · 2.3.2 ⑦ · infra-spec 2.1 · 2.3 · R4-16 · R-03 · J94 · J134)
#
# 빌드 맥락은 frontend/ 이다(frontend/.dockerignore가 .next/standalone과 .next/static만 들인다). 이미지 안에서 npm을
# 돌리지 않고 호스트(검증 명령 A · CI frontend 작업)가 `next build`로 만든 standalone 출력을 COPY한다(R-03).
#
#   docker build -f frontend/Dockerfile \
#     --build-arg GIT_SHA=<40자 커밋> --build-arg MUJOONG_SOURCE_FINGERPRINT=sha256:<64자> frontend
#   (보통은 infra/ci/build-images.sh가 부른다)
#
# - 환경 차이는 런타임 환경 변수뿐이다(J94). NEXT_PUBLIC_* 를 쓰지 않고 비밀이 없다(NFR9.10).
#   HOSTNAME=0.0.0.0 · PORT=3000은 Compose environment가 준다(infra-spec 3.7, R4-16).
# - 비루트 uid 1000(node), 최소 HTTP 클라이언트 wget(busybox)으로 컨테이너 안 /healthz를 부른다(infra-spec 2.3).
# - read_only 루트에서 쓰는 곳은 tmpfs /tmp · /app/.next/cache 뿐이다(Compose, infra-spec 2.2).

ARG NODE_IMAGE=node:22.22.3-alpine3.23

# 1단계: standalone 출력과 정적 파일을 한 디렉터리로 모은다(server.js가 .next/static을 직접 서빙한다)
FROM ${NODE_IMAGE} AS bundle
WORKDIR /bundle
COPY .next/standalone/ ./
COPY .next/static/ ./.next/static/
# 빌드 캐시 · 소스맵이 standalone에 섞여 들어왔으면 버린다(실행에 필요 없음)
RUN rm -rf ./.next/cache \
 && find . -name '*.map' -type f -delete

# 2단계: 실행 이미지
FROM ${NODE_IMAGE}
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.title="mujoong-web" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1
WORKDIR /app
COPY --from=bundle /bundle/ ./
USER 1000:1000
EXPOSE 3000
CMD ["node", "server.js"]
