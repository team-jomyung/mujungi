FROM nginx:1.30.5-alpine

# 다른 이미지와 같은 추적 라벨(build-images.sh가 빌드 인자로 넘긴다)
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"

COPY nginx-ecs/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/snippets/security-headers.conf /etc/nginx/mujoong/snippets/security-headers.conf
COPY nginx/snippets/proxy-common.conf /etc/nginx/mujoong/snippets/proxy-common.conf