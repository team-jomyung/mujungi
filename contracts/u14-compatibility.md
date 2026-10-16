# U14 계약 변경 설계

## 엄격 응답 계약

기존 session-result-response.schema.json의 additionalProperties=false, reasonText 필수, sceneNo enum[1,2]는 새 displayEvidence/timing·양의 sceneNo와 호환되지 않는다(U14-b). 새 스키마는 legacy reasonText와 생성 후보 displayEvidence를 분리하고 새 장면 timing을 허용한다. 프런트 API 파서는 새 화면 근거를 기존 모델로 변환한다. 생산부·엄격 소비자를 같은 릴리스에 배포해야 한다. 27 chip enum은 유지한다. 자세한 결정은 docs/units/u14/design.md D1/D4다.

## 내부 AI와 U15 연결

새 후보의 recommendationId는 불변 후보 버전 UUID 핸들이다. 기존 선택 요청은 recommendationId 하나를 유지하며 서버는 이 핸들이 가리키는 버전을 고정한다. currentVersion 변경은 이전 핸들의 의미를 바꾸지 않는다. 활성 선택 중복은 안정된 candidateRef로 판정한다.

목표 후보 4요소는 situation/partner/intent/expression이다. 새 씨앗 연결에 필요한 다섯 구조 서명은 내부 전용 필드로 후보 스키마·프롬프트·검증을 같이 확장했다(U14-a). 임의 상수 서명을 쓰지 않는다. 공개 DTO에는 fourElements/skillRef/내부 reasonText/구조 서명을 넣지 않는다.

## 적용과 검증

Code Generation에서 schemas·parent-api·ai-io·deviations와 대역/계약 시험을 맞췄다. 새 결과 작업은 총4회(5·15·30초 추가 재시도)다. 해당 키는 Core 소유이므로 Core application.yml과 timing 정본을 갱신하며, 키가 없는 RT/웹에 사용하지 않는 설정을 복제하지 않는다. U14TimingContractTest가 이 소유권과 값 일치를 검사한다. practice-record-v2의 flowVersion과 기존 snapshotVersion 의미는 바꾸지 않는다. 실공급자/기기는 사용하지 않았으며 전체 compose·PR CI는 별도 최종 검증이다.
