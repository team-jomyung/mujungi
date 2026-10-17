FROM nginx:1.30.5-alpine
COPY nginx-ecs/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/snippets/security-headers.conf /etc/nginx/mujoong/snippets/security-headers.conf
COPY nginx/snippets/proxy-common.conf /etc/nginx/mujoong/snippets/proxy-common.conf