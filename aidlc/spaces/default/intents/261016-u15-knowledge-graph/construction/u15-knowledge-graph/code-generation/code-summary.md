# U15 코드 요약

## 구현과 경계

아동별 그래프 포트, 원본 ZIP 14개 씨앗 로더, PostgreSQL 예약/성공 원장, 명시적 Neo4j 트랜잭션을 구현했다. 권한·동의·삭제 상태를 조회와 생성 저장 직전에 확인한다. 원격 쓰기 결과가 불명확하면 성공으로 승격하지 않고 핸들 종료·후보 삭제 후 회수한다.

VariantPlanner는 SAME_CONTEXT/NEAR 한 요소 변경과 최근 노출 회피를 결정적으로 수행한다. 표면 어휘는 POC의 CHANGE 지시를 아동용 유한 목록으로 구체화했으며 contracts/knowledge-graph.md에 명시했다. SeedContentValidator는 NFKC, 개인정보/금지 표현, 원문 연속 5글자, 한 문장 60 코드포인트 경계를 적용한다. 실제 원문은 AI 입력에 넣지 않는다.

공유 LunaCallBudget는 기술 재시도 1회, 스키마/내용 수정 슬롯 1회, 전체 전송 3회와 최초 마감을 보존한다. 등록 성공 뒤 초기화, 계정 삭제 순서 80, 기록/아동 삭제 표식, 꺼짐 및 키 없는 기본 경로를 연결했다.

## 검증과 제한

첫 검증은 U15 96개 포함 134개 통과, U15 줄 854/939(90.95%) 관문 통과, 호스트 도구 37개와 비밀 환경 파일 분배 검사 통과다. 검토 수정 뒤 최신 수치는 docs/units/u15/report.md에 추가한다. 전체 회귀·verify.sh·원격 CI 및 push는 부모 Build and Test에서 남아 있다. 실 AI/기기 검증은 수행하지 않았다.

## 검토 수정

수정 후 선택 회귀 193개(U15 100개) 통과, U15 줄 876/962(91.06%) 관문 통과. required-sections 및 traceability 센서는 모두 pass=true, finding 0이다.

삭제된 기록의 성공 씨앗을 성공 원장 조회에서 제외하므로 목록·맥락·재구성·AI 목록이 동일하게 숨긴다. 실패 정리는 현재 invocation이 생성한 정확한 활동/씨앗 예약만 변경한다. 고정된 5종 검증 코드를 중복 없이 저장하며 성공 씨앗에도 복사한다. SkillContext는 동일 보호자 잠금 안에서 읽은 씨앗과 graphVersion을 함께 반환한다. V8 추가에 따라 기존 마이그레이션/스키마/기동 시험 5개 파일의 정확한 이력·표 목록·낮은 버전 fixture를 갱신했다.

기존 공개 Lookup과 생성자 호환을 유지했다. 새 설정 record를 계약 비교 목록에 추가했고 시간 예산은 바꾸지 않았다. Duration 단위 변환 한 줄의 정적 검사 근거를 추가했다. JaCoCo가 원본 ZIP의 CP437 파일명을 클래스 아카이브로 오해하지 않도록 report/verification에 mainClassesDirs를 먼저 지정하고 기존 coverageScope 필터를 뒤에서 적용한다. 운영 클래스 제외와 하한 완화는 없다.

source-manifest.json은 이 단계 운영/시험/설정/문서 변경 목록이며 traceability.json은 27개 FR과 동번호 BR 및 NFR을 연결한다. U14/U10 호출 연결은 범위 밖이다.
