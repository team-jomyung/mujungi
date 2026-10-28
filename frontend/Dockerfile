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
# - 기반 이미지는 태그와 다이제스트를 함께 고정한다(J349). 22.23.3-alpine3.23(Alpine 3.23.6)은 libssl3 · libcrypto3 3.5.8-r0를
#   담는다(이전 22.22.3-alpine3.23은 3.5.6-r0, 이미지 점검 ③). Node 판은 .nvmrc(22)의 CI 빌드 판과 같은 22 계열이다.
# - 실행 단계에는 npm · npx · corepack · yarn이 없다. standalone 서버는 node만 쓰고, 그 도구 안의 꾸러미(tar · pacote 등)는
#   취약점 스캔 대상만 늘린다(J349).

ARG NODE_IMAGE=node:22.23.3-alpine3.23@sha256:baf676f7d0e552f3231945c2f979055ca121bce128c152f7a34e6bd1728b1c5a

# 1단계: standalone 출력과 정적 파일을 한 디렉터리로 모은다(server.js가 .next/static을 직접 서빙한다)
FROM ${NODE_IMAGE} AS bundle
WORKDIR /bundle
COPY .next/standalone/ ./
COPY .next/static/ ./.next/static/
# public은 standalone에 자동 포함되지 않는다. 글꼴 · 라이선스 · 화면 그림 · 3D 모델만 명시적으로 포함한다.
COPY public/fonts/pretendard/PretendardVariable.woff2 ./public/fonts/pretendard/PretendardVariable.woff2
COPY public/fonts/pretendard/LICENSE.txt ./public/fonts/pretendard/LICENSE.txt
COPY public/brand/mujoong-character.png ./public/brand/mujoong-character.png
COPY public/models/Explorer_A_Web.meshopt.glb ./public/models/Explorer_A_Web.meshopt.glb
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
# 패키지 관리 도구를 지운다(node · docker-entrypoint.sh만 남는다, J349)
RUN rm -rf /usr/local/lib/node_modules /opt/yarn-v* \
 && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg
WORKDIR /app
COPY --from=bundle /bundle/ ./
USER 1000:1000
EXPOSE 3000
CMD ["node", "server.js"]
