# 베이스는 태그와 다이제스트를 함께 고정한다(R-M4). 1.30.5 기반 이미지에서 고친 판이 있는 HIGH 이상 취약점 3건이 나와
# 이미지 점검 ③이 막아 1.30.6으로 올렸다(JIRA-269). 판을 올릴 때는 새 태그의 다이제스트로 함께 바꾼다
FROM nginx:1.30.6-alpine@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951

# 베이스 이미지 alpine 패키지 보안 패치 적용.
# 2026-10-20 Trivy HIGH 5건(libexpat·libx11·pcre2·tiff)이 패치 버전은 있으나 상위 이미지에 미반영.
# 상위 nginx 이미지가 재배포되면 이 줄의 필요 여부를 다시 확인한다.
RUN apk upgrade --no-cache

# 다른 이미지와 같은 추적 라벨(build-images.sh가 빌드 인자로 넘긴다)
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"

COPY nginx-ecs/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/snippets/security-headers.conf /etc/nginx/mujoong/snippets/security-headers.conf
COPY nginx/snippets/proxy-common.conf /etc/nginx/mujoong/snippets/proxy-common.conf
COPY seed-audio/ /srv/mujoong/approved-audio/
