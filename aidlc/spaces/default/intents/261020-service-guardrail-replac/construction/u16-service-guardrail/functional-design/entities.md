# U16 논리 데이터 모델

## Sources

- ../../../inception/requirements-analysis/requirements.md: FR1–FR7, NFR1–NFR6.
- ../../../inception/units-generation/unit-of-work.md 및 unit-of-work-story-map.md: U16 서버 책임과 U17 소비 경계.
- ../../../inception/domain-design/components.md: 소유·참조, 상태 권위는 기존 세션 실행기 하나.
- ../../../inception/contract-design/contract-summary.md: C1–C6 및 GuardrailPresentation.
- ../../../inception/user-stories/stories.md: 8개 스토리의 AC, 협력 부분을 포함하되 전체 스토리 완료와 구별.
- functional-design-questions.md: 기존 음성 활용·후속 자원 정리, GPT-Live 사전 낭독/파일화 가능성.

## Entity Model

아래 YAML은 자료형·속성/허용값·소유의 정본이다. 기존 세션/이벤트의 최소 확장이며 새 독립 서비스/장기 기억 저장소가 아니다. 식별자는 내부 참조이고 모델 입력/공개 표시 필드를 뜻하지 않는다.

```yaml
{
  "entities": [
    {
      "name": "PolicyBundle",
      "description": "앱에 포함해 세션에 고정하는 불변 정책",
      "owner": "ServicePolicyCatalog",
      "identifier": "promptVersion",
      "attributes": [
        {
          "name": "promptVersion",
          "type": "bounded-text",
          "required": true,
          "unique": true,
          "min": 1,
          "max": 80
        },
        {
          "name": "policyVersion",
          "type": "bounded-text",
          "required": true,
          "min": 1,
          "max": 80
        },
        {
          "name": "mode",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "FREE_TALK",
            "PRACTICE"
          ]
        },
        {
          "name": "sourceHash",
          "type": "sha256",
          "required": true,
          "constraints": [
            "원본 UTF-8 바이트 SHA256, 소문자16진64자리"
          ]
        },
        {
          "name": "appliedHash",
          "type": "sha256",
          "required": true,
          "constraints": [
            "앱 적용 자원의 실제 UTF-8 바이트 해시"
          ]
        },
        {
          "name": "instructions",
          "type": "nonempty-text",
          "required": true
        },
        {
          "name": "approvedNoticeTextHash",
          "type": "sha256",
          "required": false
        },
        {
          "name": "serviceFactsRevision",
          "type": "bounded-text",
          "required": true,
          "min": 1,
          "max": 80
        },
        {
          "name": "noticeAsset",
          "type": "ApprovedNoticeAsset",
          "required": false
        }
      ],
      "constraints": [
        "동일 promptVersion은 동일 바이트/모드/사실 판에만 사용",
        "자유대화 묶음에 연습 정책을 대입하지 않음",
        "정본의 미구현 서비스 주장은 적용본에서 사실로 교정하고 편차 기록"
      ],
      "relationships": [
        {
          "to": "SessionConversation",
          "cardinality": "1:N",
          "direction": "referenced-by"
        }
      ]
    },
    {
      "name": "SessionConversation",
      "description": "기존 세션에 속한 정책 상태와 효과의 단일 소유",
      "owner": "SessionLifecycle",
      "identifier": "sessionId",
      "attributes": [
        {
          "name": "sessionId",
          "type": "opaque-session-id",
          "required": true,
          "unique": true
        },
        {
          "name": "mode",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "FREE_TALK",
            "PRACTICE"
          ]
        },
        {
          "name": "promptVersion",
          "type": "policy-reference",
          "required": false,
          "references": "PolicyBundle.promptVersion"
        },
        {
          "name": "status",
          "type": "existing-session-status",
          "required": true
        },
        {
          "name": "statusVersion",
          "type": "nonnegative-integer",
          "required": true
        },
        {
          "name": "generationId",
          "type": "nonnegative-integer",
          "required": true,
          "default": 0
        },
        {
          "name": "noticeEvidenceId",
          "type": "optional-id",
          "required": false,
          "references": "NoticeDeliveryEvidence.noticeEvidenceId"
        },
        {
          "name": "participationDisposition",
          "type": "enum",
          "required": true,
          "default": "UNKNOWN",
          "allowed_values": [
            "UNKNOWN",
            "PENDING",
            "ACCEPTED",
            "REFUSED",
            "NO_RESPONSE"
          ]
        },
        {
          "name": "policyState",
          "type": "ConversationPolicyState",
          "required": true
        },
        {
          "name": "stopConfirmation",
          "type": "enum",
          "required": true,
          "default": "IDLE",
          "allowed_values": [
            "IDLE",
            "ASKING",
            "WAITING"
          ]
        },
        {
          "name": "immediateMute",
          "type": "boolean",
          "required": true,
          "default": false
        },
        {
          "name": "endCause",
          "type": "existing-end-reason",
          "required": false
        },
        {
          "name": "goodbyePlaybackEvidence",
          "type": "optional-current-playback",
          "required": false
        },
        {
          "name": "confirmedStopExpressionReference",
          "type": "optional-transcript-seq",
          "required": false,
          "min": 1
        },
        {
          "name": "deviceStopConfirmation",
          "type": "existing-confirmation",
          "required": false
        }
      ],
      "constraints": [
        "상태/효과 변경은 세션의 직렬 처리 경계만 수행",
        "ACTIVE 전 참여전사/원음을 새로 영속하지 않음",
        "정책 자원 실패 시 이전 역할로 fallback하지 않음",
        "현재 수락은 현재 안내 완료 뒤 새 입력이어야 함"
      ],
      "relationships": [
        {
          "to": "PolicyBundle",
          "cardinality": "N:0..1",
          "direction": "references"
        },
        {
          "to": "NoticeDeliveryEvidence",
          "cardinality": "1:N",
          "direction": "owns"
        },
        {
          "to": "SessionPresentationFacts",
          "cardinality": "1:N",
          "direction": "owns-revisions"
        },
        {
          "to": "OutputCandidateBinding",
          "cardinality": "1:N",
          "direction": "referenced-by"
        }
      ]
    },
    {
      "name": "NoticeDeliveryEvidence",
      "description": "안내 재생 시도별 현재성 및 실제 완료 근거",
      "owner": "SessionLifecycle",
      "identifier": "noticeEvidenceId",
      "attributes": [
        {
          "name": "noticeEvidenceId",
          "type": "opaque-id",
          "required": true,
          "unique": true
        },
        {
          "name": "sessionId",
          "type": "session-reference",
          "required": true,
          "references": "SessionConversation.sessionId"
        },
        {
          "name": "playbackId",
          "type": "opaque-playback-id",
          "required": true,
          "unique": true
        },
        {
          "name": "connectionId",
          "type": "opaque-current-connection-id",
          "required": true
        },
        {
          "name": "promptVersion",
          "type": "policy-reference",
          "required": true,
          "references": "PolicyBundle.promptVersion"
        },
        {
          "name": "policyVersion",
          "type": "bounded-text",
          "required": true
        },
        {
          "name": "noticeTextHash",
          "type": "sha256",
          "required": true
        },
        {
          "name": "approvedAudioVersion",
          "type": "opaque-content-version-id",
          "required": true
        },
        {
          "name": "audioHash",
          "type": "sha256",
          "required": true
        },
        {
          "name": "requestedAt",
          "type": "server-instant",
          "required": true
        },
        {
          "name": "deadline",
          "type": "server-instant",
          "required": true
        },
        {
          "name": "reportedAt",
          "type": "device-instant",
          "required": false
        },
        {
          "name": "confirmedAt",
          "type": "server-instant",
          "required": false
        },
        {
          "name": "deliveryResult",
          "type": "enum",
          "required": true,
          "default": "PENDING",
          "allowed_values": [
            "PENDING",
            "COMPLETED",
            "FAILED",
            "INVALIDATED"
          ]
        }
      ],
      "constraints": [
        "성공은 승인된 말소리 자원과 현재 유효한 COMPLETED 수신 모두 필요",
        "ACK/STARTED/톤·무음/말풍선은 실제 안내 완료 근거가 아님",
        "serverRecvAt >= deadline이면 만료 우선",
        "기기 reportedAt은 서버 마감/현재성 결정을 덮지 못함",
        "중단·실패·대체·연결 무효화 뒤 늦은 완료는 no-op"
      ],
      "relationships": [
        {
          "to": "SessionConversation",
          "cardinality": "N:1",
          "direction": "references"
        },
        {
          "to": "PolicyBundle",
          "cardinality": "N:1",
          "direction": "references"
        }
      ]
    },
    {
      "name": "OutputCandidateBinding",
      "description": "기존 출력 후보/검수의 현재 귀속",
      "owner": "OutputGuard",
      "identifier": "candidateId",
      "attributes": [
        {
          "name": "candidateId",
          "type": "opaque-id",
          "required": true,
          "unique": true
        },
        {
          "name": "sessionId",
          "type": "session-reference",
          "required": true,
          "references": "SessionConversation.sessionId"
        },
        {
          "name": "promptVersion",
          "type": "policy-reference",
          "required": true,
          "references": "PolicyBundle.promptVersion"
        },
        {
          "name": "servicePolicyVersion",
          "type": "bounded-text",
          "required": true
        },
        {
          "name": "jevPolicyVersion",
          "type": "bounded-text",
          "required": true
        },
        {
          "name": "mode",
          "type": "existing-output-mode",
          "required": true
        },
        {
          "name": "generationId",
          "type": "nonnegative-integer",
          "required": true
        },
        {
          "name": "contentHash",
          "type": "sha256",
          "required": true
        },
        {
          "name": "audioAlignment",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "UNKNOWN",
            "MATCHED",
            "INVALID"
          ]
        },
        {
          "name": "decision",
          "type": "enum",
          "required": true,
          "default": "PENDING",
          "allowed_values": [
            "PENDING",
            "ALLOW",
            "BLOCK",
            "REVIEW",
            "FAILED"
          ]
        },
        {
          "name": "outputPurpose",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "GENERAL",
            "REST_ACK",
            "APPROVED_NOTICE",
            "STOP_CONFIRM",
            "APPROVED_GOODBYE",
            "PRACTICE"
          ]
        }
      ],
      "constraints": [
        "후보·검사 결과의 연결은 서버 내부에만 유지",
        "서비스 프롬프트 판을 JEV 정책 판 대신 사용하지 않음",
        "ALLOW는 현재 actor 재확인 뒤에만 효과 가능",
        "새 범주/추가 자동 호출 없음"
      ],
      "relationships": [
        {
          "to": "SessionConversation",
          "cardinality": "N:1",
          "direction": "references"
        },
        {
          "to": "PolicyBundle",
          "cardinality": "N:1",
          "direction": "references"
        }
      ]
    },
    {
      "name": "SessionPresentationFacts",
      "description": "기존 세션 이벤트에 남기는 최소 확정 사실의 불변 개정",
      "owner": "SessionLifecycle",
      "identifier": "factEventId",
      "attributes": [
        {
          "name": "factEventId",
          "type": "opaque-id",
          "required": true,
          "unique": true
        },
        {
          "name": "sessionId",
          "type": "session-reference",
          "required": true,
          "references": "SessionConversation.sessionId"
        },
        {
          "name": "revision",
          "type": "nonnegative-integer",
          "required": true
        },
        {
          "name": "statusVersion",
          "type": "nonnegative-integer",
          "required": true
        },
        {
          "name": "schemaVersion",
          "type": "integer",
          "required": true,
          "allowed_values": [
            1
          ],
          "default": 1
        },
        {
          "name": "notice",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "UNKNOWN",
            "PENDING",
            "COMPLETED",
            "FAILED"
          ],
          "default": "UNKNOWN"
        },
        {
          "name": "participation",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "UNKNOWN",
            "PENDING",
            "ACCEPTED",
            "REFUSED",
            "NO_RESPONSE"
          ],
          "default": "UNKNOWN"
        },
        {
          "name": "goodbye",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "UNKNOWN",
            "NONE",
            "PENDING",
            "PLAYING",
            "COMPLETED",
            "STOPPED",
            "FAILED"
          ],
          "default": "UNKNOWN"
        },
        {
          "name": "confirmedStopBubbleSeq",
          "type": "optional-integer",
          "required": false,
          "min": 1
        },
        {
          "name": "recordedAt",
          "type": "server-instant",
          "required": true
        }
      ],
      "constraints": [
        "기존 Realtime 세션 이벤트 보존/삭제/권한에 귀속, 새 원문 저장 없음",
        "critical 전이와 같은 원자적 저장 경계에 기록",
        "재시작 후 동적 PLAYING/PENDING을 실제 진행으로 복구하지 않음",
        "조회는 기존 상태와 맞는 확정 사실만 사용, 모순/잘못된 개정은 unknown",
        "정본 원문 대신 기존 허용 전사 seq만 참조"
      ],
      "relationships": [
        {
          "to": "SessionConversation",
          "cardinality": "N:1",
          "direction": "references"
        }
      ]
    }
  ],
  "value_objects": [
    {
      "name": "ApprovedNoticeAsset",
      "attributes": [
        {
          "name": "contentVersionId",
          "type": "opaque-version",
          "required": true
        },
        {
          "name": "textHash",
          "type": "sha256",
          "required": true
        },
        {
          "name": "audioHash",
          "type": "sha256",
          "required": true
        },
        {
          "name": "audioLocation",
          "type": "approved-relative-path",
          "required": true
        },
        {
          "name": "durationMs",
          "type": "positive-integer",
          "required": true,
          "max": 20000
        },
        {
          "name": "verification",
          "type": "enum",
          "required": true,
          "allowed_values": [
            "PLACEHOLDER",
            "SPOKEN_VERIFIED"
          ]
        }
      ],
      "constraints": [
        "기존 톤 파일은 PLACEHOLDER이며 APPROVED 상태 행만으로 SPOKEN_VERIFIED 승격 금지",
        "유료 생성/사람 청취 전에는 검증 완료 판을 발급하지 않음",
        "파일 실제 해시/형식/길이와 원문 대조 결과를 고정"
      ]
    },
    {
      "name": "ConversationPolicyState",
      "attributes": [
        {
          "name": "refusedRequest",
          "type": "optional-current-request-reference",
          "required": false
        },
        {
          "name": "helpUsed",
          "type": "boolean",
          "required": true,
          "default": false
        },
        {
          "name": "resting",
          "type": "boolean",
          "required": true,
          "default": false
        },
        {
          "name": "currentRequest",
          "type": "optional-current-request-reference",
          "required": false
        }
      ],
      "constraints": [
        "현재 세션 메모리에서만 유지, 원문/새 장기 기억 영속 금지",
        "같은 정보 요구의 재표현/기술 재시도로 helpUsed 초기화 금지",
        "서버가 의미를 확정하지 못하면 새 확인 요구를 강제하지 않는 보수적 선택",
        "임의 모델 발화의 의미 준수는 실모델 평가와 구분"
      ]
    }
  ]
}
```

## Entity Summary

| 소유 | 엔티티 | 수명 |
|---|---|---|
| ServicePolicyCatalog | PolicyBundle | 앱 자원 판, 세션 시작에 고정 |
| SessionLifecycle | SessionConversation, NoticeDeliveryEvidence | 현재 세션과 재생 시도, 단일 상태 권위 |
| OutputGuard | OutputCandidateBinding | 기존 검수 후보 수명 |
| SessionLifecycle | SessionPresentationFacts | 기존 세션 이벤트와 같은 보존/삭제 수명 |

ConversationPolicyState는 현재 세션의 작은 값 상태이고 새 전사 복제/장기 기억이 아니다. 실제 음성 준비 전의 PLACEHOLDER는 대역 재생 시험 자료로 활용하되 실제 안내 완료의 근거로 사용하지 않는다.

## Assumptions & Open Questions

실제 음성 제작/검수는 functional-spec.md의 후속 항목을 따른다. 기존 승인 카탈로그/경로를 사용하며 사용자 원본 documents를 실행 중 읽지 않는다.

