FROM nginx:1.30.5-alpine

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