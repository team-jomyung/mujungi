# U16 업무 규칙

## Sources

- ../../../inception/requirements-analysis/requirements.md: FR1–FR7, NFR1–NFR6.
- ../../../inception/units-generation/unit-of-work.md 및 unit-of-work-story-map.md: U16 서버 책임과 U17 소비 경계.
- ../../../inception/domain-design/components.md: 소유·참조, 상태 권위는 기존 세션 실행기 하나.
- ../../../inception/contract-design/contract-summary.md: C1–C6 및 GuardrailPresentation.
- ../../../inception/user-stories/stories.md: 8개 스토리의 AC, 협력 부분을 포함하되 전체 스토리 완료와 구별.
- functional-design-questions.md: 기존 음성 활용·후속 자원 정리, GPT-Live 사전 낭독/파일화 가능성.

## Business Rules

아래 YAML이 판단 규칙의 정본이다. 순서/상태 전이는 functional-spec.md가 정본이다. 규칙은 자동 요청·상태 검증의 기준이며 임의 모델 발화의 의미 품질 보장을 뜻하지 않는다.

```yaml
{
  "rules": [
    {
      "id": "BR1.1",
      "statement": "모드에 맞는 비어 있지 않은 고정 정책만 사용",
      "category": "validation",
      "applies_to": "PolicyBundle",
      "trigger": "세션 시작",
      "logic": "IF 자유대화 THEN service-0.1 적용본을 한 번 선택하고 기존 첫 질문 강제를 제거한다. IF 누락/빈 값/해시 불일치 THEN 일반 대화를 열지 않는다.",
      "violation_behaviour": "기존 기술 실패로 정리, 옛 템플릿 자동 대체 금지",
      "source": [
        "FR1.1",
        "FR1.2",
        "FR7.3"
      ]
    },
    {
      "id": "BR1.2",
      "statement": "정본/적용본과 앱 산출물을 추적",
      "category": "constraint",
      "applies_to": "PolicyBundle",
      "trigger": "정책 적재/빌드",
      "logic": "IF 적용본이 정본과 다르면 편차와 실제 바이트 SHA256을 기록한다. 앱 포함 자원만 읽으며 세션 중 바꾸지 않는다.",
      "violation_behaviour": "정책 자원 오류, 일반 출력 없음",
      "source": [
        "FR1.2",
        "FR1.3",
        "NFR5"
      ]
    },
    {
      "id": "BR1.3",
      "statement": "최소 아이 문맥을 데이터로만 전달",
      "category": "validation",
      "applies_to": "DialoguePolicy",
      "trigger": "초기/후속 요청 구성",
      "logic": "IF 이름/나이/관심사를 포함하면 기존 상한·이스케이프를 적용하고 내부 식별자/비밀/타인 정보를 추가하지 않는다. 제어 문자가 지시로 실행되지 않게 한다.",
      "violation_behaviour": "허용값만 구성하거나 명시적 요청 실패",
      "source": [
        "FR5.2",
        "NFR1"
      ]
    },
    {
      "id": "BR1.4",
      "statement": "기존 공급자·연습 요청 형식 유지",
      "category": "constraint",
      "applies_to": "DialoguePolicy",
      "trigger": "요청 송신",
      "logic": "IF 자유대화 THEN 새 정책을 선택한다. IF 연습 THEN 기존 역할/검증된 발화 입구를 유지한다. 네 송신 type·모델·PCM형식을 유지하고 구현 계약의 키를 공급자 필드로 복사하지 않는다.",
      "violation_behaviour": "지원 밖 요청 거부",
      "source": [
        "FR7.1",
        "FR7.3",
        "NFR6"
      ]
    },
    {
      "id": "BR2.1",
      "statement": "승인된 실제 안내 자원을 확인",
      "category": "validation",
      "applies_to": "ApprovedNoticeAsset",
      "trigger": "매 자유대화 안내 준비",
      "logic": "IF 텍스트/음성 판·해시/필수 사실·형식·길이와 SPOKEN_VERIFIED가 모두 맞으면 실제 안내 재생 후보를 만든다. PLACEHOLDER는 전달 완료 증거가 아니다.",
      "violation_behaviour": "실제 일반 대화 보류, 기존 파일은 대역 연결 자료로만 사용",
      "source": [
        "FR2.1",
        "FR2.2",
        "FR5.1"
      ]
    },
    {
      "id": "BR2.2",
      "statement": "현재 실제 완료만 안내 증거로 인정",
      "category": "authorization",
      "applies_to": "NoticeDeliveryEvidence",
      "trigger": "재생 표식 수신",
      "logic": "IF 현재 연결/세션/재생ID/정책이 일치하고 무효화되지 않았으며 서버 수신시각이 deadline 미만이고 COMPLETED이면 현재 완료를 한 번 기록한다. ACK/다운로드/STARTED만으로 완료하지 않는다.",
      "violation_behaviour": "타세션·지난·중복 표식은 no-op, 실패/만료는 기술 정리",
      "source": [
        "FR2.2",
        "FR2.5",
        "NFR2"
      ]
    },
    {
      "id": "BR2.3",
      "statement": "안내 누락·실패·만료를 기술 실패로 처리",
      "category": "policy",
      "applies_to": "SessionLifecycle",
      "trigger": "안내 자원/전송/재생 실패",
      "logic": "IF 자원/송신/재생 실패 또는 완료 시한 만료 THEN 일반 출력을 닫고 기존 원인별 기술 실패/기기 정리를 수행한다. 기기 무응답은 DEVICE_NO_ACK, 공급자 시작 실패는 PROVIDER_UNAVAILABLE이다.",
      "violation_behaviour": "완료 추정·아이 거절/무응답으로 번역 금지",
      "source": [
        "FR2.2",
        "FR2.4",
        "FR6.1"
      ]
    },
    {
      "id": "BR2.4",
      "statement": "안내 뒤 새 참여 수락만 ACTIVE로 전환",
      "category": "authorization",
      "applies_to": "SessionConversation",
      "trigger": "참여 입력/판정 수신",
      "logic": "IF 실제 안내 완료 뒤 새 입력이 현재 참여 창에 속하고 현재 수락이면 저장 전이 승자만 ACTIVE/원음 기록/첫 응답을 한 번 실행한다. 안내 중 입력과 그 늦은 판정은 무효다.",
      "violation_behaviour": "일반 출력 잠금 유지, 중복 시작 금지",
      "source": [
        "FR2.3",
        "FR2.5",
        "NFR2"
      ]
    },
    {
      "id": "BR2.5",
      "statement": "명확한 거절과 무응답을 구별",
      "category": "policy",
      "applies_to": "SessionConversation",
      "trigger": "참여 거절/창 만료",
      "logic": "IF 명확한 거절 THEN REFUSED와 DECLINED를 원자적으로 기록하고 대안/만류하지 않는다. IF 실제 안내 뒤 기존 20초와 재안내 뒤20초 모두 무응답 THEN NO_RESPONSE로 정리한다. 불명확 답만 기존 한 차례 짧은 확인을 허용한다.",
      "violation_behaviour": "참여 전 전사 삭제/비영속, 원음 기록 시작 금지",
      "source": [
        "FR2.4",
        "FR6.1",
        "FR6.3"
      ]
    },
    {
      "id": "BR2.6",
      "statement": "정책/참여 상태를 모델에 중복 안내 없이 전달",
      "category": "policy",
      "applies_to": "DialoguePolicy",
      "trigger": "ACTIVE 첫 응답 구성",
      "logic": "IF 현재 안내/참여가 확인됐으면 안내/참여 질문을 다시 시작하지 않는 고정 상태 문구를 사용한다. 내부 ID/해시/이전 세션의 완료는 전달하지 않는다.",
      "violation_behaviour": "현재 확인이 없으면 일반 응답 생성 금지",
      "source": [
        "FR2.5",
        "FR5.2"
      ]
    },
    {
      "id": "BR3.1",
      "statement": "아이 화제·질문과 출처를 우선",
      "category": "policy",
      "applies_to": "DialoguePolicy",
      "trigger": "첫/일반 응답 지시",
      "logic": "IF 이미 아이 질문/경험이 있으면 그것을 먼저 따른다. 막힌 때만 주요 질문 또는 설명 요청 하나를 허용하며 자동 관심사 질문/답 뒤 새 질문/실제 경험 수집 강제는 하지 않는다. 상상·보고·추정을 실제 아이 경험으로 합치지 않는다.",
      "violation_behaviour": "강제 질문 템플릿 사용 금지",
      "source": [
        "FR3.1",
        "FR5.3"
      ]
    },
    {
      "id": "BR3.2",
      "statement": "거절·휴식에는 기다리고 아이가 연 화제를 따름",
      "category": "policy",
      "applies_to": "ConversationPolicyState",
      "trigger": "거절/휴식/새 화제 지시",
      "logic": "IF 화제 거절/휴식이면 수용 뒤 새 질문·재개 압박 없이 기다린다. 거절 요청은 차단하고 의미가 불명확하면 재요구를 강제하지 않는다. 아이가 명시적으로 새 화제/거절 화제를 열 때만 해당 맥락으로 이동한다.",
      "violation_behaviour": "서버의 REST_ACK_AND_RETURN/옛 강제 첫 질문으로 뒤집지 않음",
      "source": [
        "FR3.2",
        "FR4.1"
      ]
    },
    {
      "id": "BR3.3",
      "statement": "같은 정보 요구의 도움을 한 번으로 제한",
      "category": "constraint",
      "applies_to": "ConversationPolicyState",
      "trigger": "서버가 추가 설명/도움을 선택",
      "logic": "IF 같은 현재 정보 요구이고 helpUsed=false이며 실제 이해 실패 근거가 있으면 한 번만 도움을 허용하고 true로 바꾼다. 재표현/기술재시도는 같은 요청이다. 명시적 새 화제/다른 요구가 아니면 초기화하지 않는다.",
      "violation_behaviour": "근거 불명확/이미 사용/거절은 새 도움·재질문 강제 없음",
      "source": [
        "FR3.3",
        "FR3.4"
      ]
    },
    {
      "id": "BR3.4",
      "statement": "정정·끼어들기와 지시 범위를 존중",
      "category": "policy",
      "applies_to": "DialoguePolicy",
      "trigger": "후속/교정/재시도 요청",
      "logic": "IF 정정/끼어들기면 취소한 말/질문을 잇지 않는다. 모든 후속 지시는 이번 응답의 범위를 닫고 이후 기본 정책을 회복한다. 이해 실패 근거 없는 재시도는 실패를 꾸미지 않고 새 질문·고정 맞장구를 강제하지 않는다.",
      "violation_behaviour": "이전 지속 지시의 반복/취소 질문 재개 금지",
      "source": [
        "FR3.3",
        "FR3.4"
      ]
    },
    {
      "id": "BR4.1",
      "statement": "명확한 전체 종료를 바로 처리",
      "category": "policy",
      "applies_to": "SessionLifecycle",
      "trigger": "종료 의미 수신",
      "logic": "IF 등록 또는 비등록의 명확한 현재 전체 종료이면 CHILD_STOP으로 연결하고 출력 세대를 무효화한다. 이유/재확인/만류 없음. 짧은 승인 인사는 최대1회, 즉시 음소거는 추가 발화0회.",
      "violation_behaviour": "같은 종료 재확인 금지, 출력 보류/큐 정리",
      "source": [
        "FR4.1",
        "FR4.3"
      ]
    },
    {
      "id": "BR4.2",
      "statement": "정말 모호한 종료만 한 번 확인",
      "category": "policy",
      "applies_to": "SessionConversation",
      "trigger": "모호한 종료 후보/현재 확인 답",
      "logic": "IF 진짜 모호하면 일반 대화를 보류하고 짧게 한 번 확인한다. 현재 창의 명확한 전체 종료 답은 바로 종료, 명확한 계속 답은 재개한다. 미확인/무응답은 종료 동의가 아니며 기존20초 끝에서 기존 재개를 따른다. 질문 중 조기 긍정을 새 확인 답으로 재사용하지 않는다. IF 질문 전달 실패/만료/중단이면 현재 질문·재생·대답 창/시계를 무효화하고 질문 보류만 해제한다. 여전히 ACTIVE이고 연결/기기/공급자가 정상이면 자동 질문/재개 발화 없이 듣기로 복귀하며, 지속 장애는 기존 기술 정리다. 종료·다른 현재 보류를 해제하거나 늦은 완료로 창을 복구하지 않는다.",
      "violation_behaviour": "중복 질문/모호한 긍정으로 강제 종료 금지",
      "source": [
        "FR4.2",
        "FR6.1"
      ]
    },
    {
      "id": "BR4.3",
      "statement": "실제 로컬/J6 요청의 종료 구분 기준을 시험",
      "category": "validation",
      "applies_to": "DialoguePolicy",
      "trigger": "판정 요청/결과 적용",
      "logic": "IF 인용/역할 대사/종료 부정/화제만 거절이면 명확한 전체 종료와 구분한다. J6 고정 질문·선택지/문맥이 실제 어댑터 요청에 전달되는지 검증한다. 결과 주입만으로 검증했다고 하지 않는다.",
      "violation_behaviour": "유효하지 않거나 지난 판정은 보류/no-op, 새 자동 판정 호출 없음",
      "source": [
        "FR4.1",
        "FR4.2",
        "FR7.1"
      ]
    },
    {
      "id": "BR4.4",
      "statement": "모든 비동기 결과의 현재 귀속 재확인",
      "category": "authorization",
      "applies_to": "SessionLifecycle",
      "trigger": "판정·검수·음성·표식 적용",
      "logic": "IF session/mode/generation/현재 상태/목적과 기대 ID가 모두 맞을 때만 효과를 실행한다. 종료·끼어들기·연결 무효화 뒤 지난 결과는 일반 출력을 열지 못한다.",
      "violation_behaviour": "결과/큐 폐기, 중복 ACTIVE/재생 없음",
      "source": [
        "FR4.3",
        "FR7.2",
        "NFR2"
      ]
    },
    {
      "id": "BR5.1",
      "statement": "서비스 기능과 완료 여부를 꾸미지 않음",
      "category": "policy",
      "applies_to": "PolicyBundle/DialoguePolicy",
      "trigger": "안내/기본·후속 문구 구성",
      "logic": "IF 저장/열람/수동 공유/삭제를 설명하면 확인된 현재 기능만 사용하고 원본과 차이를 추적한다. 삭제 접수/완료·원음재생·외부PDF삭제·긴급 연락·모드전환은 확인 없이 가능/완료로 말하지 않는다.",
      "violation_behaviour": "새 권한/보존 정책 자동 도입 금지",
      "source": [
        "FR5.1",
        "FR6.3"
      ]
    },
    {
      "id": "BR5.2",
      "statement": "비밀·장기 기억·진단·대리 조사 약속 금지",
      "category": "policy",
      "applies_to": "DialoguePolicy",
      "trigger": "정책/문맥 전달",
      "logic": "IF 비밀/삭제/보호자 조사 요청이면 새 권한/기능 실행으로 해석하지 않는다. 제공되지 않은 기억/진단/점수/성격은 만들지 않고 보호자 보고·아이 경험·추정을 구분한다.",
      "violation_behaviour": "새 장기기억/관찰결과 기능 없음",
      "source": [
        "FR5.1",
        "FR5.3"
      ]
    },
    {
      "id": "BR5.3",
      "statement": "최소 확정 사실을 기존 세션 수명에 보존",
      "category": "constraint",
      "applies_to": "SessionPresentationFacts",
      "trigger": "확정 사실/중요 상태 전이",
      "logic": "IF 현재 notice/participation/종료 참조를 확정하면 기존 세션 이벤트에 최소 개정을 남기고 관련 상태 전이와 원자적으로 맞춘다. 저장 실패 시 전이/효과 성공으로 처리하지 않는다. 타세션/미확정 전사참조/원문을 저장하지 않는다.",
      "violation_behaviour": "정본 없는 사실은 null/UNKNOWN, 기기 출력 먼저 차단",
      "source": [
        "FR5.1",
        "FR6.3",
        "NFR1",
        "NFR2"
      ]
    },
    {
      "id": "BR5.4",
      "statement": "조회는 사실 부재와 실제 재생을 구별",
      "category": "policy",
      "applies_to": "SessionPresentationFacts",
      "trigger": "부모/내부 조회",
      "logic": "IF CLOSING 진입이면 이전 재생 사실을 해제한다. IF 현재 승인 인사 STARTED가 확인되면 CLOSING 중에도 PLAYING이며, 그 인사의 완료/중단/실패·단절/만료 또는 최종 종료에서 해제한다. actor 없음/재시작/지연에서는 PLAYING을 복구하지 않는다. Core fallback은 stale와 무관하게 guardrail=null이다. 인용은 현재 CHILD_STOP의 가시적 final/확실한 CHILD seq만 제공한다.",
      "violation_behaviour": "기본 종료 문구/인사/거절·무응답 추정 금지",
      "source": [
        "FR4.3",
        "FR5.1",
        "NFR6"
      ]
    },
    {
      "id": "BR6.1",
      "statement": "기존 시간·제외/경합 의미 유지",
      "category": "calculation",
      "applies_to": "SessionLifecycle",
      "trigger": "시계/연결 상태 변화",
      "logic": "IF 참여20초/재안내20초/공백4분·6분/재접속40초/종료확인30초/모호종료20초에 도달하면 기존 의미를 적용한다. 재생/출력대기/기술일시정지/확인질문 제외를 유지하고 동일 deadline은 만료 우선이다.",
      "violation_behaviour": "장애를 침묵으로 오인하지 않음, 새 총 세션/호출 상한 없음",
      "source": [
        "FR6.1",
        "FR6.2"
      ]
    },
    {
      "id": "BR6.2",
      "statement": "기존 기록/동의/삭제·증거 구분 보존",
      "category": "constraint",
      "applies_to": "SessionLifecycle",
      "trigger": "시작/기록/정리/검증 보고",
      "logic": "IF ACTIVE 전이면 참여전사 분류만 하고 새 원음 영속기록 없음. 기존 동의/권한/삭제/수동 공유 회귀를 유지한다. 대역 성공과 실제 말소리·기기 청취·모델 품질을 구분한다.",
      "violation_behaviour": "실제 음성 미준비 항목을 완료로 보고하지 않음",
      "source": [
        "FR6.3",
        "NFR3",
        "NFR4"
      ]
    },
    {
      "id": "BR7.1",
      "statement": "기존 JEV 결과·정렬·판·연습 경계 유지",
      "category": "authorization",
      "applies_to": "OutputGuard",
      "trigger": "후보 검사/출력 승인",
      "logic": "IF 완결/현재 후보의 음성 대응과 기존7범주·확률/형식 검증에 통과했을 때만 ALLOW 후보로 돌려준다. 실패/REVIEW/타임아웃/불완전/정렬미확인은 보류/폐기한다. 현재 actor 확인을 다시 받아야 한다.",
      "violation_behaviour": "자동 ALLOW·금지범주 완화·새 분류기/호출 없음",
      "source": [
        "FR7.2",
        "FR7.3",
        "NFR6"
      ]
    }
  ]
}
```

## Rules Summary

| Rule | 내용 |
|---|---|
| BR1.1 | 모드에 맞는 비어 있지 않은 고정 정책만 사용 |
| BR1.2 | 정본/적용본과 앱 산출물을 추적 |
| BR1.3 | 최소 아이 문맥을 데이터로만 전달 |
| BR1.4 | 기존 공급자·연습 요청 형식 유지 |
| BR2.1 | 승인된 실제 안내 자원을 확인 |
| BR2.2 | 현재 실제 완료만 안내 증거로 인정 |
| BR2.3 | 안내 누락·실패·만료를 기술 실패로 처리 |
| BR2.4 | 안내 뒤 새 참여 수락만 ACTIVE로 전환 |
| BR2.5 | 명확한 거절과 무응답을 구별 |
| BR2.6 | 정책/참여 상태를 모델에 중복 안내 없이 전달 |
| BR3.1 | 아이 화제·질문과 출처를 우선 |
| BR3.2 | 거절·휴식에는 기다리고 아이가 연 화제를 따름 |
| BR3.3 | 같은 정보 요구의 도움을 한 번으로 제한 |
| BR3.4 | 정정·끼어들기와 지시 범위를 존중 |
| BR4.1 | 명확한 전체 종료를 바로 처리 |
| BR4.2 | 정말 모호한 종료만 한 번 확인 |
| BR4.3 | 실제 로컬/J6 요청의 종료 구분 기준을 시험 |
| BR4.4 | 모든 비동기 결과의 현재 귀속 재확인 |
| BR5.1 | 서비스 기능과 완료 여부를 꾸미지 않음 |
| BR5.2 | 비밀·장기 기억·진단·대리 조사 약속 금지 |
| BR5.3 | 최소 확정 사실을 기존 세션 수명에 보존 |
| BR5.4 | 조회는 사실 부재와 실제 재생을 구별 |
| BR6.1 | 기존 시간·제외/경합 의미 유지 |
| BR6.2 | 기존 기록/동의/삭제·증거 구분 보존 |
| BR7.1 | 기존 JEV 결과·정렬·판·연습 경계 유지 |

## Assumptions & Open Questions

실제 음성 생성/청취·승인 이전에는 기존 시험 톤을 실제 안내로 승인하지 않는다. GPT-Live 사전 생성은 후속 자원 확보 경로로 설계하되 실제 유료 호출은 아직 승인/실행하지 않았다. U17의 표현/접근성 구현은 해당 단위가 맡는다.

