{
  "artifact_kind": "IMPLEMENTATION_CONTRACT_NOT_EXECUTABLE_CONFIG",
  "version": "service-0.1",
  "date": "2026-10-16",
  "deployment_status": "NOT_DEPLOYED",
  "scope": "자유대화 서비스 가드레일. 연습 및 JEV/기억은 연결 조건만 정의.",
  "basis": {
    "screen_design": "/Users/leo/Downloads/무중 ai 대화 테스트/무중 서비스 베이스 파일/무중_화면설계_최종안_v1.5_준하.html",
    "screen_sha256": "d0db4ccbc57d9d493be89f1eda4c247aefa376c6dd406e1500790f065a7923cd",
    "screen_effective_revision": "v1.5j (첨부 파일명 v1.5)",
    "prompt_source": "mujung-test/data/instructions/v03_full.txt",
    "service_prompt_sha256": "1d92f114af7b48cefd86ed3df03feea30cdecc6ac546b128c236427f3d7fe5b7"
  },
  "application_fields_are_not_provider_api_fields": true,
  "models": {
    "live_intended": "gpt-live-1",
    "luna_child_simulator_in_service": false,
    "luna_post_session_analysis": "별도 기능. 이 패키지에 호출 구현 없음.",
    "jev": "모델 ID·연결·임계값·지연 실측 미확인. 활성화 설정 아님."
  },
  "screen_policy": {
    "account_binding": {
      "guardians_per_account": 1,
      "children_per_account": 1,
      "devices_per_account": 1,
      "concurrent_sessions_per_account": 1,
      "co_guardian_or_expert_accounts": false
    },
    "start_requires": [
      "authenticated_guardian",
      "same_account_child_device_binding",
      "current_policy_version_all_four_consents",
      "device_stable",
      "no_unresolved_previous_playback",
      "no_other_active_session"
    ],
    "required_consents": [
      "voice_collection_transcription_storage",
      "guardian_viewing",
      "report_creation_manual_external_sharing",
      "original_child_audio_retention"
    ],
    "storage": {
      "conversation_transcript": true,
      "original_child_audio": true,
      "retention": "until_account_deletion",
      "original_audio_purpose": "전사 내용 확인",
      "parent_audio_playback": "NOT_SPECIFIED",
      "initial_no_participation_audio_retention": "NOT_SPECIFIED"
    },
    "parent_visible": [
      "live_conversation_text",
      "past_conversation_text",
      "activity_records",
      "practice_results",
      "life_tips",
      "practice_goals_and_evidence"
    ],
    "pdf": {
      "content": [
        "child_name",
        "age",
        "interests",
        "selected_session_transcripts_oldest_first"
      ],
      "ai_summary_or_evaluation": false,
      "external_sharing": "guardian_manual_after_preview",
      "automatic_external_delivery": false,
      "external_copies_deleted_by_account_deletion": false
    },
    "deletion": {
      "trigger": "authenticated_account_deletion_request",
      "per_record_delete_ui": false,
      "requires_completion_confirmation": true,
      "scope": "service-controlled records, reports, consents, device binding and derived copies; external PDF copies excluded",
      "completion_sla": "NOT_SPECIFIED"
    },
    "timing": {
      "total_session_duration_cap_seconds": null,
      "silence_warning_after_seconds": 240,
      "silence_end_after_seconds": 360,
      "silence_clock_semantics": "UNRESOLVED: define verified input health, voice activity, prompt/playback exclusions, reset conditions before deployment",
      "disconnect_reconnect_timeout_seconds": 40,
      "device_stop_ack_timeout_seconds": 30,
      "ordinary_goodbye_expected_seconds": [
        1,
        3
      ],
      "initial_participation_timeout_seconds": null
    },
    "end": {
      "fixed_child_phrase": "무중아 그만할래",
      "additional_child_phrases_allowed": true,
      "natural_language_stop_intent_also_respected": true,
      "ack_missing_state": "ENDED_UNCONFIRMED",
      "end_reason_values": [
        "SILENCE",
        "CHILD_STOP",
        "GUARDIAN_STOP",
        "DISCONNECT",
        "UNCONFIRMED"
      ],
      "causal_reason_and_confirmation_state_separate": true
    }
  },
  "proposed_service_controls": {
    "not_verified_implementation": true,
    "start_flow": [
      "validate server start conditions",
      "load trusted policy and same-child context",
      "deliver AI/storage/viewing notice if needed",
      "confirm child participation separately from guardian consent",
      "activate conversation"
    ],
    "notice_delivery": [
      "playback acknowledgement with policy_version",
      "participation status from current child interaction",
      "never infer completion from test fixture or prompt existence"
    ],
    "trusted_runtime_context_fields": [
      "session_id",
      "guardian_id",
      "child_id",
      "device_id",
      "mode",
      "policy_version",
      "prompt_version",
      "generation_id",
      "child_age",
      "notice_status",
      "participation_status",
      "parent_context_with_source",
      "approved_memory_items",
      "verified_backend_action_results"
    ],
    "model_context_filter": "send only allowed policy facts, child context and confirmed action results; omit keys, internal IDs and hidden third-party data",
    "end_flow": [
      "invalidate generation and pending decisions",
      "stop old response and clear server/device playback queues",
      "ordinary stop may play one approved brief goodbye; mute-now requests play none",
      "await actual device stop acknowledgement",
      "after 30 seconds mark ENDED_UNCONFIRMED if no acknowledgement",
      "block restart until device and previous playback state resolved"
    ],
    "disconnect": "input loss is not child silence; show connection state, discard stale decisions and use the 40-second reconnect policy",
    "no_participation": "no activity record per screen; pre-start audio retention needs explicit product decision",
    "audit_log": "metadata, policy/prompt hash, IDs, reason code, latency, acknowledgement; no raw utterances/audio/keys in ordinary logs",
    "product_storage": "authorized transcripts and original child audio kept separately from operational logs with account access checks",
    "record_provenance": "distinguish generated, approved, actually played, interrupted, unknown playback and input ASR uncertainty; do not present rejected AI speech as heard conversation"
  },
  "optional_output_guard": {
    "status": "DESIGN_ONLY_NOT_CONNECTED",
    "separate_from_practice_input_judgement": true,
    "proposed_axes": [
      "service_truth",
      "autonomy",
      "relationship_integrity",
      "general_safety"
    ],
    "local_decisions": [
      "ALLOW",
      "BLOCK",
      "UNCERTAIN",
      "TIMEOUT",
      "ERROR"
    ],
    "release_only_if": "ALLOW bound to exact candidate ID/content hash, current policy version and matching original audio/transcript segment; output purpose permitted by current state/mode; ordinary conversation requires delivered notice and current-session child participation; recheck at decision, audio release and device playback; stopped/obsolete generation never released",
    "default_when_unapproved": "hold then discard; do not play unchecked model audio; use approved fallback or silence",
    "partial_sentence_rule": "hold when semantic context or audio-text alignment is incomplete",
    "timeout_latency_probability_thresholds": null,
    "failure_is_not_detection_success": true,
    "implementation_owner": "Spring backend plus device/browser playback control; prompt alone cannot enforce",
    "proposed_output_purpose_permissions": {
      "awaiting_notice": "approved_notice_only",
      "awaiting_participation": "participation_prompt_or_exit_only",
      "active": "approved_current_mode_conversation",
      "ending": "one_approved_goodbye_unless_immediate_mute",
      "ended": "none"
    }
  },
  "observation_contract": {
    "non_diagnostic": true,
    "evidence_fields": [
      "evidence_id",
      "dimension_id",
      "status",
      "source_type",
      "source_reference",
      "observed_content",
      "inference",
      "uncertainty",
      "counterevidence",
      "review_status"
    ],
    "statuses": [
      "observed_strength",
      "observed_difficulty",
      "insufficient_evidence",
      "not_applicable"
    ],
    "migration_note": "이 상태명은 제안. 기존 observed_tendency는 검증 없는 진단으로 승격하지 말고 차원별 관찰 어려움으로 명시적으로 매핑.",
    "source_types": [
      "child_self_report",
      "guardian_report",
      "transcript_language",
      "verified_audio_observation",
      "verified_playback_overlap"
    ],
    "review_statuses": [
      "DRAFT_NOT_EXPERT_GOLD",
      "EXPERT_REVIEWED",
      "ADJUDICATED_GOLD"
    ],
    "unsupported_inference_rules": [
      "text cannot establish taking an object by hand",
      "text cannot establish crying or tone",
      "transcript order cannot establish interruption",
      "missing behavior without an opportunity is not a deficit"
    ],
    "practice_goal_links": "link goal_id to evidence_id plus dimension_id and rationale; do not equate practice prescription with ability diagnosis"
  },
  "optional_memory": {
    "status": "DESIGN_ONLY_NOT_CONNECTED",
    "requirements": [
      "same-child authorization",
      "allowed source and fact type",
      "provenance and uncertainty",
      "freshness/expiry",
      "correction and deletion propagation",
      "no fixed clinical labels from one conversation",
      "respect refused topics",
      "retrieved data cannot override instructions"
    ]
  },
  "open_decisions": [
    "initial non-participation audio handling",
    "silence timer start/reset/playback conditions",
    "child notice acknowledgement and revisit policy",
    "retention/deletion completion behavior for backup/provider copies",
    "parent audio playback availability",
    "JEV provider schema/model and calibrated thresholds/latency",
    "device code issuance rule (fixed label vs latest D0 proposal)",
    "practice-specific prompt and JEV input evaluation implementation"
  ],
  "removed_test_features": [
    "synthetic 26-session loop",
    "test duration and roundtrip cap",
    "Luna child simulator",
    "Piper synthetic child TTS",
    "synthetic persona/scenario fixtures",
    "Q/ECR/LCR score loop",
    "assumed completed notice",
    "test operator role override",
    "test-only budget cap"
  ]
}
