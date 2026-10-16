# U15 구현

전체 Compose 검증을 수동 스택과 동시에 실행할 때는 검증 전용의 비어 있는 대역을 `MUJOONG_VERIFY_EDGE_PREFIX`와 `MUJOONG_VERIFY_BACKEND_PREFIX`로 지정할 수 있다. 기본 네트워크 불변식과 비밀 전달 검사는 유지하며 수동 스택은 변경하지 않는다. 재현 명령은 infra/RUNBOOK.md의 검증 스택 설명을 따른다.

아동별 지식 그래프는 ChildKnowledgeService 내부 포트로 제공한다. 공개 HTTP API는 추가하지 않았다. 등록 트랜잭션이 끝난 뒤 초기화를 예약하고 장애는 등록 성공에 영향을 주지 않는다. 조회 전체는 기존 2초 예산으로 제한하며 늦게 끝난 읽기 작업은 초기화 쓰기를 예약하지 않는다.

기본 자료는 원본 social-comm-poc.zip에서 세 지정 파일을 제한된 크기로 읽는다. 14개 씨앗과 가족별 보존 규칙을 검증하고 아동용 관계/자리로 변환한다. 원본은 변경하지 않는다. 동일 버전의 다른 해시는 PostgreSQL과 Neo4j 양쪽에서 거절한다. Neo4j는 명시적 트랜잭션과 유일 제약을 사용한다.

생성은 T1 예약, 외부 모델 호출, T2 빈 Neo4j 트랜잭션 핸들 기록, T3 투영과 성공 원장 확정으로 나눈다. 보호자 잠금 아래 권한·현재 동의·삭제 상태를 재확인한다. 결과가 불명확한 쓰기는 핸들 종료와 후보 노드 제거 후 NONE으로 회수하며 성공으로 추정하지 않는다. 성공 원장 없는 그래프 노드는 목록에서 숨긴다. 예약 번호는 재사용하지 않는다.

LunaCallBudget는 기술 재시도 1회와 스키마/내용 수정 슬롯 1회, 전체 전송 3회 및 최초 시한을 공유한다. 생성 프롬프트에는 추상화 구조와 활성 씨앗 목록만 전달한다. 전사·배경 JSON에서 추출한 문자열은 서버 안에서만 내용 검증에 사용한다. NFKC 정규화, 개인정보/금지 표현, 연속 5글자 겹침, 불변 문장 60 코드포인트와 한 문장 조건을 검사한다.

기록 삭제 표식과 아동 삭제 표식은 늦은 응답의 재생성을 차단한다. 계정 삭제 참가자 순서는 80이며 외부 종료가 불명확하거나 꺼진 그래프의 과거 이력이 있으면 예외로 기존 RETRY를 유지한다. 독립 아동 삭제 표식은 아동이 아직 ACTIVE여도 재초기화를 막고 계정 삭제는 보호자 차단 뒤 표식을 정리한다.

U14는 결과 작업에서 생성 포트를 호출하고 skillRef를 연결한다. U10은 실제 노출을 전달하고 변형 계획을 대본/보존 판정에 함께 사용한다. 이 호출 연결은 이번 U15에 포함되지 않는다. 표면 어휘와 설정은 contracts/knowledge-graph.md를 따른다.

검토 보완: skillContext는 SkillContext(seed, graphVersion)를 반환하며 둘은 동일 보호자 잠금 안에서 읽는다. 삭제 표식이 있는 기록의 성공 씨앗은 읽기·중복 선택·AI 목록·재구성 모두에서 제외하되 정리 대상 ID는 보존한다. 실패 정리는 해당 호출이 생성한 정확한 activityId/seedId 예약에만 적용한다. checkCodes는 CONTENT_ACCEPTED, CONTENT_REJECTED, MODEL_NONE, DUPLICATE_REUSED, GENERATION_FAILED의 유한 목록에서 중복 없이 기록하며 성공 시 generated_seed에도 복사한다. 원문/예외 메시지는 이 기록에 포함하지 않는다.

호환 조정: 기존 KnowledgeProperties(boolean) 생성자와 KnowledgeGraphLookup 단일 메서드를 유지했다. ChildProfileService의 초기화 연결은 부분 Spring 컨텍스트에서도 기존 등록이 가능하도록 선택 주입한다. 새 설정 record는 ConfigKeyContractTest의 비교 목록에 등록했고 Duration 단위 변환 한 줄만 시간 리터럴 검사 근거 목록에 추가했다. JaCoCo는 CP437 원본 ZIP을 클래스 ZIP으로 해석하지 않도록 mainClassesDirs를 사용하며 범위 필터와 모든 운영 클래스 및 80% 하한을 유지한다.
