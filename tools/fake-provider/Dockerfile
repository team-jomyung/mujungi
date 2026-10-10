# 가짜 공급자 이미지 — dev · ci Compose 서비스 전용 (U1 3.5 Step 45 · J110 · J129 · SD-25 · RD-23)
#
# 앱 이미지(core-api · realtime · web)에 넣지 않는 시험 도구다. 빌드 문맥은 tools/ 이고(tools/.dockerignore가 필요한 파일만 남김)
# Dockerfile은 fake-provider/Dockerfile이다:
#   docker build -f tools/fake-provider/Dockerfile -t mujoong-fake-provider:local tools
# 컨테이너 안 받기(npm)를 하지 않는다: 검증 명령 A가 호스트에서 'cd tools && npm ci'로 잠금 파일대로 설치한 node_modules/ws를
# 복사한다(샌드박스 빌드 규칙 R-03 · Step 43과 같은 방식 — 컨테이너 안 프로세스는 프록시 · CA를 모른다). 비밀 · 프록시 값은 없다.
FROM node:22.22.0-bookworm-slim

ARG GIT_SHA=unknown
ARG MUJOONG_SOURCE_FINGERPRINT=unknown
LABEL org.opencontainers.image.title="mujoong-fake-provider" \
      org.opencontainers.image.description="무중 가짜 공급자(시험 전용, J110 선 형식)" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}" \
      kr.mujoong.test-only="true"

ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
COPY node_modules/ws ./node_modules/ws
COPY lib ./lib
COPY fake-provider ./fake-provider

# 공식 이미지의 비루트 사용자(uid 1000)
USER node
EXPOSE 8090
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=12 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:8090/healthz').then((r) => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"]
ENTRYPOINT ["node", "fake-provider/server.mjs"]
CMD ["--host", "0.0.0.0", "--port", "8090", "--scenario", "ok", "--turns", "normal,forbidden"]
