# U6 결과와 리포트 설계

정본은 `aidlc/spaces/default/intents/261014-u6-result-report/construction/functional-design/`의 functional-spec.md, entities.md, rules.md, frontend-components.md다. 이 문서는 구현 인계용 요약이다.

## 구조와 데이터

Core의 `result` 모듈이 DB 작업 실행, 정리 대상 판별, 기록용 재판정, 문장 검사와 결과 조회를 맡고 `report` 모듈이 기간 선택, 10개 항목 조립, 저장·버림과 PDF를 맡는다. 운영 마이그레이션은 V5 하나다. ResultJob·ResultJobAttempt·SessionResultState·ActivityResult·SceneJudgment·StructuredExperience·Report·ReportActivity·ReportSection·ReportCitation 10개 표를 추가하고 support_item에는 nullable title을 더한다.

BR1–2: REQUESTED 작업을 DB에서 선점해 RUNNING으로 바꾸고 성공은 DONE, 실패는 설정된 간격으로 재시도하거나 FAILED로 닫는다. 총 시도는 최초 포함 3회다. 작업 스레드 2개 중 하나는 REPORT를 전담한다. AI 호출은 트랜잭션 밖이며 완료 시 lease와 보호자 삭제 상태를 다시 검사한다. 이벤트와 누락 점검은 같은 판별·접수 경로를 쓴다. 재시작 FAILED는 확정 발화가 있는 경우에만 대상이다.

BR3–7: 자유대화 구조화와 연습 재판정을 구분한다. 이름과 내부 식별자를 AI에 넘기지 않는다. 인용 대조, 금지 사전, 분류 검사, 숫자 검사를 거치며 실패 문장은 검증된 템플릿으로 바꾼다. INSUFFICIENT는 AI 없이 완료한다. 후보 등록은 결과 커밋 뒤에 실행하며 재접수로 복구한다. 생활 팁은 행동 키가 맞는 항목만 읽고 금지 표현을 걸러 낸다.

BR8–10: 보호자 API는 진행 중 판정·원시 구조화·배경 원문을 반환하지 않는다. 리포트 생성은 요청 키·본문 해시로 멱등 처리하며 요청부터 60초 절대 마감을 둔다. 서버 집계 항목은 1·3·4·7·8·9, AI 항목은 2·5·6·10이다. 결과 값은 DONE 활동만, 전사 인용과 활동 집계는 선택 활동 전체를 사용한다. 인용 후보는 활동당 5개·항목당 30개다.

리포트 전이는 GENERATING → READY/FAILED, READY → SAVED/DISCARDED다. 버림은 하위 내용을 삭제하고 묘비를 남긴다. SAVED는 삭제 요청까지 보존하며 저장 안 된 READY·FAILED는 설정된 24시간 뒤 정리한다. PDF를 받은 뒤에만 saved를 요청한다.

BR11: 화면 17은 정리 상태, 26은 달력·목록, 27은 결과와 세 탭, 28은 기간·활동 선택과 생성, 30은 10항목·PDF 저장을 제공한다. 정리/생성 중에만 2초 폴링하고 화면을 떠나거나 최종 상태이면 멈춘다. 날짜·칩·상태는 글자로도 표현하며 상태 변경은 aria-live로 알린다.

BR12–14: U5a의 ScenarioLookup·BehaviorDefinitionLookup·CandidateRegistry 읽기 경로를 확장했다. U4a 기록은 공개 ResultRecordReader를 통해 읽는다. 결과 삭제 참가자 순서는 70이고 기록 삭제 90보다 먼저 U6 하위 행부터 지운다. 삭제 중 접수와 늦은 결과 쓰기를 차단한다. 재시작 FAILED의 표시에는 기존 종료 사유 문구를 사용한다.

## 계약과 설정

REST 정본은 `contracts/parent-api.yaml`과 `contracts/schemas/`다. AI 정본은 `contracts/ai-io.md` 6절이다. 시간 값은 `contracts/timing.md`, application.yml, CoreProperties/TextAiProperties 및 프런트 timing 설정에 연결한다. 결과 상태·리포트 조회는 로그인 소유자를 검사하고 쓰기는 기존 CSRF 경로를 따른다. 남의 리소스는 404다. 편차 목록은 `contracts/deviations.md`의 U6-a–U6-l을 따른다.

U6-J: AI 작업 형식·프롬프트·금지 사전·팁 시드는 D 담당 확인 전 초안이다. U6-K: Core 단일 인스턴스 전제에서 시작 시 RUNNING을 FAILED로 닫는다. 다중 인스턴스 전환은 만료 lease만 복구하는 설계 변경이 필요하다. U5b 반응 사상표, 자유대화 문장의 팀장 확인, Android Chrome 저장·공유는 별도 인계 사항이다.

## PDF 리소스와 제약

동일 HEAD `7a22b67`의 깨끗한 임시 worktree에서 기준선 bootJar와 같은 Dockerfile·기반 이미지로 다시 빌드했다. `docker image inspect .Size` 기준 이전 `mujoong-core-api:u6-baseline`은 545,066,875바이트, U6 `mujoong-core-api:u6-verify`는 564,883,067바이트다. 전체 이미지 증가량은 **19,816,192바이트(약 18.90MiB)**다. bootJar 자체는 67,798,104 → 75,873,129바이트(+8,075,025)다. 아래의 비압축 리소스 합계와 전체 이미지 차이는 측정 대상이 다르다.

OpenHTMLtoPDF core/pdfbox 1.0.10(LGPL 2.1 이상), PDFBox·FontBox·XmpBox 2.0.24(Apache 2.0), pdfbox-graphics2d 0.32(Apache 2.0)를 사용한다. 글꼴은 Noto Sans KR Regular/Bold, 내부 name ID 5 버전 `2.004-H2;hotconv 1.0.118;makeotfexe 2.5.65603`, SIL Open Font License 1.1이다. 저작권·원문 라이선스는 `backend/core-api/src/main/resources/report/fonts/OFL.txt`에 있다.

현대 한글 11,172자 전체와 ASCII·Latin-1·한글 자모·문장부호를 포함한다. 재생성 도구는 fonttools 4.66.1이며 원본 가변 글꼴의 wght=400/700을 고정한 후 다음 범위를 서브셋한다(명령은 각 굵기마다 실행).

```sh
fonttools varLib.instancer NotoSansKR.ttf wght=400 --output NotoSansKR-Regular.ttf
pyftsubset NotoSansKR-Regular.ttf --unicodes=U+0020-007E,U+00A0-00FF,U+1100-11FF,U+2000-206F,U+3000-303F,U+3130-318F,U+AC00-D7A3 --output-file=NotoSansKR-Regular-subset.ttf
fonttools varLib.instancer NotoSansKR.ttf wght=700 --output NotoSansKR-Bold.ttf
pyftsubset NotoSansKR-Bold.ttf --unicodes=U+0020-007E,U+00A0-00FF,U+1100-11FF,U+2000-206F,U+3000-303F,U+3130-318F,U+AC00-D7A3 --output-file=NotoSansKR-Bold-subset.ttf
```

실제 체크인된 Regular는 2,769,608바이트, Bold는 2,769,584바이트다. 라이브러리 6개·글꼴·라이선스·템플릿의 비압축 합계는 11,349,236바이트(약 10.82MiB)이며 이것은 해당 리소스의 이미지 기여량이다. 전체 이미지의 기준선 대비 차이와 혼동하지 않는다.

PDF는 저장된 항목으로 요청마다 메모리 바이트 배열을 생성한다. 첫 렌더에서 PDFBox 기본 OS 폰트 탐색이 `.pdfbox.cache`를 만들던 결함을 발견하여 EmbeddedFontMapper가 클래스패스 두 글꼴만 메모리에 공급하도록 했다. 새 JVM·빈 tmp/home 테스트로 캐시 없는 첫 렌더와 한글 추출을 검증한다. 컨테이너 제한은 기존 1.6g, tmpfs 64m, 읽기 전용 루트를 유지한다. 최종 실행 수치는 report.md에 남긴다.
