# U4b 엔터티 모델 — 활동 세션 Realtime 쪽

> Functional Design(3.1) · 작업 기록 `261013-u4b-activity-realtime`.
>
> 입력
> - `inception/requirements-analysis/requirements.md`(FR1 ~ FR11)
> - `docs/units/u4b/design.md` 6절
> - codekb `component-inventory.md` · `code-quality-assessment.md`
> - realtime V1 · V2 마이그레이션의 실제 열
>
> 이 작업 기록은 units-generation · domain-design을 건너뛰었으므로 기존 코드 구조를 사실상의 도메인 설계로 본다. 아래 YAML이 정본이다. 기존 표의 열은 V1 · V2에 있는 것만 적었고, U4b는 기존 표에 열을 더하지 않는다.

```yaml
entities:
  - name: ActivitySession
    description: Realtime 쪽 활동 세션 한 번(기존 표 realtime_activity_session). U4b가 쓰는 열만 적는다.
    owner: realtime.session
    status: existing
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: guardian_id, type: uuid, required: true, constraints: "진행 중 세션에는 이미 부분 유일 색인이 있다. U4b는 종료 세션까지 찾는 삭제 처리기용 일반 색인을 더한다" }
      - { name: child_id, type: uuid, required: true, constraints: "SessionRecordCaptured.childId의 출처" }
      - { name: device_id, type: uuid, required: true }
      - { name: activity_type, type: enum, required: true, allowed: [FREE_TALK, PRACTICE] }
      - { name: status, type: enum, required: true, allowed: [PREPARING, DEVICE_ACK, WAITING_CHILD, ACTIVE, CLOSING, ENDED, ENDED_UNCONFIRMED, DECLINED, ABORTED, FAILED], constraints: "종료 상태(ENDED · ENDED_UNCONFIRMED · DECLINED · ABORTED · FAILED)는 되돌아가지 않는다. DB CHECK가 이미 10개를 허용한다" }
      - { name: status_version, type: integer, required: true, min: 1, constraints: "전이마다 정확히 1 증가(DB CHECK >= 1)" }
      - { name: policy_version, type: string, required: true, constraints: "스냅숏 policy_version의 출처" }
      - { name: end_reason, type: enum, required: false, allowed: [GUARDIAN_STOP, CHILD_STOP, SILENCE, DISCONNECT, CONSENT_WITHDRAWN, UNCONFIRMED, DECLINED, FAILED], constraints: "CLOSING 진입 때 마무리 사유를 쓴다. ENDED_UNCONFIRMED는 UNCONFIRMED로 덮는다. 계약 열거와 같다" }
      - { name: end_detail, type: text, required: false, constraints: "PRACTICE_COMPLETED(연습 마지막 목표 끝), ENDED_UNCONFIRMED일 때 처음 마무리 사유, FAILED일 때 실패 코드(SERVER_RESTART 등)" }
      - { name: termination_requested_at, type: timestamp, required: false, constraints: "끝내기 · 동의 철회를 받은 시각. 철회 뒤 저장 차단의 기준. termination.requestedAt" }
      - { name: output_stopped_at, type: timestamp, required: false, constraints: "STOP_OUTPUT 송신 시각. termination.outputStoppedAt" }
      - { name: transport_closed_at, type: timestamp, required: false, constraints: "CLOSE_TRANSPORT 송신 시각. termination.transportClosedAt" }
      - { name: provider_closed_at, type: timestamp, required: false, constraints: "공급자 close 시각. termination.providerClosedAt" }
      - { name: started_at, type: timestamp, required: false, constraints: "ACTIVE 진입 시각. ACTIVE 전에 끝나면 비어 있다" }
      - { name: ended_at, type: timestamp, required: false }
      - { name: created_at, type: timestamp, required: true }

  - name: ResultInputSnapshot
    description: 종료된 세션의 결과 입력 기록(새 표 realtime_result_input_snapshot, realtime V3). Core 결과 처리(U6)의 입력이며 SessionRecordCaptured와 함께 한 번 만든다.
    owner: realtime.session
    status: new
    attributes:
      - { name: id, type: uuid, required: true, unique: true, constraints: "SessionRecordCaptured.snapshotId" }
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id, constraints: "세션 삭제보다 먼저 지운다(FK RESTRICT)" }
      - { name: snapshot_version, type: integer, required: true, min: 1, default: 1, constraints: "(session_id, snapshot_version) 유일. U4b는 1만 쓴다" }
      - { name: final_status, type: enum, required: true, allowed: [ENDED, ENDED_UNCONFIRMED, DECLINED, ABORTED, FAILED] }
      - { name: end_reason, type: enum, required: true, allowed: [GUARDIAN_STOP, CHILD_STOP, SILENCE, DISCONNECT, CONSENT_WITHDRAWN, UNCONFIRMED, DECLINED, FAILED] }
      - { name: confirmed, type: boolean, required: true, constraints: "기기 종료 확인(VOICE_SESSION_CLOSED)을 받았는지" }
      - { name: policy_version, type: string, required: true }
      - { name: cutoff_at, type: timestamp, required: true, constraints: "정산 창이 끝난 시각. 이 뒤의 전사는 기록에 들어가지 않는다" }
      - { name: snapshot_hash, type: string, required: true, constraints: "'sha256:' + 16진 64자" }
      - { name: record, type: json, required: true, constraints: "SessionRecord 모양" }
      - { name: captured_at, type: timestamp, required: true }
      - { name: created_at, type: timestamp, required: true }
    constraints:
      - "세션당 한 행(snapshot_version 1)"
      - "행과 SessionRecordCaptured Outbox는 같은 트랜잭션에 쓴다"

  - name: SessionRecord
    description: 결과 스냅숏의 record 값(값 객체, 계약 6절). ResultInputSnapshot.record에 저장한다.
    owner: realtime.session
    status: new
    attributes:
      - { name: utterances, type: list, required: true, constraints: "항목: seq · speaker(CHILD · AI) · text · uncertain · startedAt · endedAt. deleted_at이 빈 행만. DECLINED · ACTIVE 전 종료는 빈 목록" }
      - { name: gaps, type: list, required: true, constraints: "항목: from · to · kind(DISCONNECT · UNCONFIRMED)" }
      - { name: recordScope, type: object, required: true, constraints: "from = started_at(없으면 created_at), to = ended_at" }
      - { name: practice, type: object, required: false, constraints: "객체 또는 null만. 확장점이 값을 주지 않으면 null" }
      - { name: audio, type: list, required: true, constraints: "항목: recordingId · speaker · storagePath(상대 경로) · startedAt · endedAt. deleted_at이 빈 메타만. DECLINED · ACTIVE 전 종료는 빈 목록" }

  - name: SessionRecordCapturedPayload
    description: SessionRecordCaptured 이벤트 페이로드(값 객체, 계약 스키마 event-payloads). 세션 행과 스냅숏에서 채운다.
    owner: realtime.session
    status: new
    attributes:
      - { name: sessionId, type: uuid, required: true, constraints: "ActivitySession.id" }
      - { name: childId, type: uuid, required: true, constraints: "ActivitySession.child_id" }
      - { name: activityType, type: enum, required: true, allowed: [FREE_TALK, PRACTICE] }
      - { name: finalStatus, type: enum, required: true }
      - { name: endReason, type: enum, required: true }
      - { name: confirmed, type: boolean, required: true }
      - { name: startedAt, type: timestamp, required: false }
      - { name: endedAt, type: timestamp, required: true }
      - { name: termination, type: object, required: true, constraints: "requestedAt · outputStoppedAt · transportClosedAt · providerClosedAt, 없는 값은 null" }
      - { name: snapshotId, type: uuid, required: true }
      - { name: snapshotVersion, type: integer, required: true }
      - { name: snapshotHash, type: string, required: true }
      - { name: record, type: object, required: true, constraints: "SessionRecord" }

  - name: Transcript
    description: 전사 한 행(기존 표 realtime_transcript, U3). U4b는 승인 문구 말풍선 행을 더한다.
    owner: realtime.log
    status: existing
    attributes:
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id }
      - { name: sequence_no, type: integer, required: true, min: 1, constraints: "(session_id, sequence_no) 유일" }
      - { name: speaker, type: enum, required: true, allowed: [CHILD, AI] }
      - { name: text, type: text, required: true }
      - { name: assembly_status, type: enum, required: true, allowed: [PARTIAL, FINAL] }
      - { name: approved_for_display, type: boolean, required: true, constraints: "승인 문구 말풍선 행은 true" }
      - { name: review_needed, type: boolean, required: true }
      - { name: started_at, type: timestamp, required: false, constraints: "늦은 전사 판정(BR8.6)과 utterances.startedAt의 출처" }
      - { name: ended_at, type: timestamp, required: false }
      - { name: deleted_at, type: timestamp, required: false, constraints: "삭제 표시된 행은 스냅숏에 넣지 않는다" }
    constraints:
      - "WAITING_CHILD 동안 아이 전사는 저장하지 않는다"
      - "DECLINED 세션은 모든 행을 지운다"

  - name: SessionGap
    description: 끊김 · 미확인 구간(기존 표 realtime_session_gap, U3). U4b는 UNCONFIRMED 구간을 실제로 쓰고 WAITING_CHILD 끊김 구간을 더한다.
    owner: realtime.log
    status: existing
    attributes:
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id }
      - { name: kind, type: enum, required: true, allowed: [DISCONNECT, UNCONFIRMED] }
      - { name: from, type: timestamp, required: true }
      - { name: to, type: timestamp, required: false, constraints: "ABORTED로 끝난 끊김 구간은 열린 채 남는다" }

  - name: SessionEvent
    description: 세션 사건 기록(기존 표 realtime_session_event). U4b는 새 사건 종류를 더한다.
    owner: realtime.session
    status: existing
    attributes:
      - { name: session_id, type: uuid, required: true, references: ActivitySession.id }
      - { name: type, type: string, required: true, constraints: "U4b가 더하는 종류: JOIN_PROMPTED · JOIN_ACCEPTED · JOIN_DECLINED · JOIN_RE_PROMPTED · JOIN_ALTERNATIVE_OFFERED · STOP_CONFIRM_ASKED · STOP_CONFIRM_RESOLVED · SILENCE_WARNED · SILENCE_RESET · TECHNICAL_PAUSE_STARTED · TECHNICAL_PAUSE_ENDED · RECORD_CAPTURED · APPROVED_CONTENT_MISSING · TRANSITION_IGNORED · PRACTICE_EXTENSION_FAILED" }
      - { name: payload, type: json, required: false, constraints: "코드 · 시각 · 횟수 · 분류 이름만. 전사 원문 · 원음 경로 금지(BR12.1)" }

  - name: TechnicalPause
    description: 기술 일시정지(값 객체, 실행기 메모리). 진행 조회와 SessionStateChanged에 실린다.
    owner: realtime.session
    status: new
    attributes:
      - { name: kind, type: enum, required: true, allowed: [DEVICE] }
      - { name: since, type: timestamp, required: true, constraints: "마지막 기기 메시지 수신 시각" }

  - name: SilenceWarning
    description: 공백 경고(값 객체, 실행기 메모리). 진행 조회에 실린다.
    owner: realtime.session
    status: new
    attributes:
      - { name: silentSince, type: timestamp, required: true }
      - { name: endsAt, type: timestamp, required: true, constraints: "6분 도달 예상 시각(멈춤이 있으면 늦춰진다)" }

  - name: DeletionParticipant
    description: Realtime 계정 삭제 참가자(새 공통 계약). 순서 값으로 호출 순서를 정하고, 기존처럼 '이름 → 건수' 지도를 돌려준다.
    owner: realtime.messaging
    status: new
    attributes:
      - { name: order, type: integer, required: true, constraints: "음성 U3 100 · 연습 U5b 150(예약) · 세션 U4b 200 · 메시징 U1 900. 작은 값부터 부른다" }
      - { name: name, type: string, required: true, unique: true }

relationships:
  - { from: ResultInputSnapshot, to: ActivitySession, cardinality: "0..1 : 1", direction: "snapshot → session" }
  - { from: ResultInputSnapshot, to: SessionRecord, cardinality: "1 : 1", direction: "snapshot contains record" }
  - { from: SessionRecordCapturedPayload, to: ResultInputSnapshot, cardinality: "1 : 1", direction: "payload built from snapshot and session" }
  - { from: Transcript, to: ActivitySession, cardinality: "0..* : 1", direction: "transcript → session" }
  - { from: SessionGap, to: ActivitySession, cardinality: "0..* : 1", direction: "gap → session" }
  - { from: SessionEvent, to: ActivitySession, cardinality: "0..* : 1", direction: "event → session" }
  - { from: TechnicalPause, to: ActivitySession, cardinality: "0..1 : 1", direction: "in-memory, per live session" }
  - { from: SilenceWarning, to: ActivitySession, cardinality: "0..1 : 1", direction: "in-memory, per live session" }
```

## 요약

- **새 저장 엔터티**: ResultInputSnapshot 하나다(realtime V3, 더하기만). 기존 세션 표에는 guardian_id 일반 색인만 더한다. 진행 중 세션의 부분 유일 색인은 이미 있다.
- **값 객체**
  - SessionRecord · SessionRecordCapturedPayload: 페이로드의 각 필드 출처를 정했다(BR8.8).
  - TechnicalPause · SilenceWarning: 살아 있는 세션의 실행기 메모리에만 있고, 종료 세션에서는 null이다.
- **기존 엔터티의 규칙 변화**
  - ActivitySession: 사유를 사건이 정한다. 연습 완료는 CHILD_STOP과 PRACTICE_COMPLETED다.
  - Transcript: 승인 문구 말풍선 행을 남기고, 참여 전 아이 전사는 저장하지 않는다. 삭제 표시 행은 스냅숏에서 뺀다.
  - SessionGap: UNCONFIRMED 구간을 쓴다.
  - SessionEvent: 새 사건 종류를 더한다.
- **새 공통 계약**: DeletionParticipant. 순서 값으로 줄을 서고, 기존 반환 모양을 유지한다.
