# ECS 승인 음성 배포

자유대화의 첫 안내는 DB가 고른 승인 콘텐츠 버전, Realtime의 검증용 리소스,
Nginx가 제공하는 WAV 파일이 일치해야 재생된다. ECS에서는 다음 세 경로를 함께 배포한다.

- `infra/seed/001~005`: migrate 이미지에 버전별 SQL로 포함한다.
  `migrate.sh`가 core → realtime 스키마 적용 후 reference를 실행한다.
  별도 이력 표 `flyway.reference_seed_schema_history`가 재적용을 막고 체크섬을 검증한다.
  기존에 수동 시딩한 DB에서도 멱등 SQL을 순서대로 실행해 새 승인 음성을 등록한다.
- `backend/realtime-activity/src/main/resources/approved-audio/`: Realtime 이미지의 검증용 음성.
  `service-policy/notice-assets.json`이 콘텐츠 버전과 본문·파일 해시를 고정한다.
- `infra/seed-audio/`: Nginx 이미지의 `/srv/mujoong/approved-audio/`에 포함한다.
  `/media/approved/<파일>.wav`로 GET/HEAD를 제공하며 이전 파일도 보존한다.

`infra/ci/build-images.sh`로 이미지를 빌드하면 migrate에 `reference_seed` 빌드 컨텍스트가 전달된다.
다른 경로에서 migrate 이미지를 직접 빌드할 때도 `--build-context reference_seed=infra/seed`를 추가한다.
참조 시드 적용 실패는 migrate 태스크 실패로 전파되어 앱 배포를 중단한다.

새 참조 시드는 기존 파일을 고치지 않고 다음 번호의 SQL을 추가한다.
migrate Dockerfile에도 해당 파일을 같은 번호의 Flyway 마이그레이션으로 포함한다.
로컬 Compose/호스트 검증이 별도로 원본 시드를 적용하는 방식도 유지된다.

배포 후 워크플로는 배포 대상 커밋의 manifest에 있는 모든 음성 URL을 내려받아
SHA-256을 대조한다. HTTP 404와 HTML 오류 페이지, 다른 버전의 파일은 배포 실패로 처리한다.
마지막으로 실제 기기에서 자유대화 시작 안내가 재생되고 참여 응답을 기다리는지 확인한다.

ROLLBACK은 기존처럼 migrate를 건너뛰며 DB의 승인 상태를 되돌리지 않는다.
새 승인 버전을 등록한 뒤에는 그 버전을 지원하는 Realtime/Nginx 이미지로 롤백해야 한다.
