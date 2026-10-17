# U14 보안·데이터 경계 시험

## 실행 검사

전체 verify.sh의 A4/P2/P3 및 backend 인증·소유권·동의·삭제·ArchUnit·계약 대조 시험을 사용한다. 신규 후보 공급자 요청의 모든 깊이에서 저장 UUID가 사라지고 불확실 전사가 제외되는 capture 시험을 포함한다. 비밀 목록·허용 목록·권한을 느슨하게 하지 않는다.

복사본 루트에서 추가로 실행한다.

```bash
bash infra/bin/infra-validate --only migrations
```

전체 인프라/의존성/이미지 보안 관문은 기존 PR CI 결과를 확인한다. 해당 CI가 완료되기 전 NFR1의 CI 부분을 통과라고 보고하지 않는다. 로컬 전체 verify는 병합 후에만 실행되는 CI skeleton을 대신하는 사전 증거다.

## 자료와 해석

실제 개인정보·키·계정 값을 시험 자료나 보고서에 넣지 않는다. V9의 기존 표 제약8개는 실제 사용자의 문장별 승인과 hash를 migration-review.md 및 infra/migration-approvals.txt에서 확인한다. V1~V8 변경 부재와 구버전 쓰기는 U14MigrationIT로 확인한다. 새 데이터 작성 뒤 구 앱 읽기 제한과 U14-a/b 공개 계약 동시 배포 요구는 유지한다. 실제 공급자·실기기 보안 평가가 끝났다고 주장하지 않는다.
