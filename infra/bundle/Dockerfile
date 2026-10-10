# 무중 인프라 번들 이미지 mujoong-infra — 실행하지 않는 파일 보관용 (U1 3.5 Step 43 · cicd 2.3.1 · 2.3.2 ⑤ · N-8 · J134)
#
# 배포 D3이 이 이미지에서 릴리스 폴더(/opt/mujoong/releases/<SHA>/)를 꺼낸다. 앱 이미지와 같은 SHA로 움직여 롤백이
# Compose 파일 · Nginx 템플릿 · 스크립트를 함께 되돌린다(cicd 2.3.1 · 다르게 푼 곳 7).
#
# 빌드 맥락은 infra/ 이고 release.env(이미지 태그 = SHA, 비밀 아님)는 build-images.sh가 release.env.tmpl로 만들어
# 이름 있는 맥락 release로 넘긴다:
#
#   docker build -f infra/bundle/Dockerfile --build-context release=<release.env가 든 임시 디렉터리> \
#     --build-arg GIT_SHA=<40자 커밋> --build-arg MUJOONG_SOURCE_FINGERPRINT=sha256:<64자> infra
#
# 담는 것은 cicd 2.3.1 목록과 호스트 기준선 host/를 하나씩 명시해 복사한다: compose.demo.yml · release.env · bin/ · nginx/ · backup/ ·
# postgres/ · seed/ · seed-audio/ · host/ · RUNBOOK.md. host/(systemd 단위 · cron · logrotate · 고정 진입점, 비밀 없음)는 Step 47이 더했다:
# 호스트에 저장소 읽기 자격을 두지 않으므로 최초 설치(RUNBOOK 4.5.1)와 배포 D9(진입점 갱신 · 기준선 차이 경고)가 같은 SHA의 번들에서
# 읽는다. compose.ci.yml · compose.dev.yml · ci/ · aws/ 와 환경 파일은 넣지 않는다(image-checks.sh 점검 ⑤ · infra-validate가 파일 목록을 단언한다).
#
# 꺼내는 법(컨테이너를 실행하지 않는다):
#   id=$(docker create <이미지>) && docker cp "$id":/bundle/. <대상> && docker rm "$id"

FROM scratch
ARG GIT_SHA
ARG MUJOONG_SOURCE_FINGERPRINT
LABEL org.opencontainers.image.title="mujoong-infra" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      kr.mujoong.source-fingerprint="${MUJOONG_SOURCE_FINGERPRINT}"
COPY compose.demo.yml /bundle/compose.demo.yml
COPY --from=release release.env /bundle/release.env
COPY bin/ /bundle/bin/
COPY nginx/ /bundle/nginx/
COPY backup/ /bundle/backup/
COPY postgres/ /bundle/postgres/
COPY seed/ /bundle/seed/
COPY seed-audio/ /bundle/seed-audio/
COPY host/ /bundle/host/
COPY RUNBOOK.md /bundle/RUNBOOK.md
# 실행 파일이 없는 이미지다. docker create가 명령을 요구하므로 자리 이름만 둔다(실행하면 실패한다).
CMD ["/bundle/not-executable"]
