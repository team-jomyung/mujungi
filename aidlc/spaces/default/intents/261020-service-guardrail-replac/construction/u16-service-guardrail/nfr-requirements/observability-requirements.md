# U16 관측·검증 요구

## Sources

- ../../../inception/requirements-analysis/requirements.md: NFR1–NFR6 및 FR1–FR7.
- ../functional-design/functional-spec.md 및 rules.md: 정책·안내/참여·종료·검수·사실 수명.
- ../../../inception/contract-design/contract-summary.md: C1–C6, 기존 시간/실패/호환 의미.
- ../../../inception/practices-discovery/team-practices.md: test-after/80%, 기존 전체 회귀와 배포/실호출 제외.
- aidlc/spaces/default/codekb/mujungi/technology-stack.md: brownfield 기술 기록. 이전 버전 표·배포 설명을 새로 인증하지 않음.

## Requirements

| ID | 요구/합격 기준 | 검증 |
|---|---|---|
| NFR1.1 | 로그는 기존 허용 식별자·판/해시·이유 코드·시각/지연만 사용하고 원문/원음/키·모델 자유서술 추가0건 | 허용 목록 대조·민감 표식 검사 |
| NFR3.1 | 변경 층 구현 뒤 해당 층 단위/핵심 통합 시험을 작성·실행한다. 기존 적용 모듈 및 기능 범위 줄80% 하한·ArchUnit/계약 검사 유지 | 기존 check/coverage 및 기능 이름 범위 결과 |
| NFR3.2 | 승인된 단위 명령으로 각 Unit 결과를 확인하고 최종 Build and Test에서 기존 bash scripts/verify.sh와 변경 경계 통합을 수행한다. 실패/전제 미준비는 PASS 아님 | 명령/종료코드/현재 소스에 대응하는 보고서 |
| NFR3.3 | actual local/J6 요청 정책, 안내/참여·시계·현재성·사실 수명과 RP 표시 소비를 분리해 시험한다. 결과 주입만으로 요청 전달 합격을 주장하지 않는다 | 실제 송신 대역과 상태효과 시험 목록 대조 |
| NFR4.1 | 자동 검증은 가짜 공급자/합성 자료다. 실모델·실기기·실아동 성공0건을 성공으로 표시하지 않고 증거 유형별 결과를 분리한다 | 최종 근거 표 |
| NFR4.3 | 실제 GPT-Live 낭독/파일화는 생성 호출과 원문·청취·길이/형식·승인 등록의 각각 실제 결과가 있어야 완료다. 미호출/미검수는 미완료 | 자원 제작 manifest·해시/판·검수 근거 |
| NFR5.1 | 정본 SHA256→적용본 판/해시·편차→앱 산출물→요청 대역/시험의 연결을 유지한다 | 깨끗한 빌드·자원/요청/편차 대조 |

## Signals and Reporting

기존 허용 로그/카운터/시험 보고서를 재사용한다. 새 운영 대시보드·외부 알림·수집기를 만들지 않는다. 잘못된 일반 출력/중복 시작은 자동 시험에서0이어야 하고, 실패 이유는 기존 원인 코드로 구별한다. 가격/계정 잔액·실제 API키·실제 아동 원문은 보고하지 않는다.

## Assumptions & Open Questions

외부 운영 지표/가용성 임계값을 새로 요구하지 않는다. 단위 검증/전체 회귀와 실제 서비스 준비 증거를 구별한다.

