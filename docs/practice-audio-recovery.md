# Practice audio boundary and bounded recovery

Controlled practice output now drains previous provider speech until the existing quiet interval before submitting the next speech instruction. The next utterance is collected from its first audio/transcript chunk, even when the append ACK arrives later. ACK context timings are not used to cut the beginning of practice audio, and the legacy 700ms speech margin is not applied to this path. Free conversation and judgment generation retain their existing boundary behavior.

Exact approved reading uses `session.instructions.append`; generated peer/coach speech uses `session.commentary.append`. Artificial spoken padding is no longer requested. Completeness, PCM format/length, forbidden-expression checks, exact approved-body matching, Jev, current generation, recorder and outbound-lane checks remain before delivery. No rejected or partial audio is replayed.

Technical empty/incomplete/no-start failures are correlated to the currently open practice slot. Approved reading gets its bounded exact retry and then a fresh generated narration from approved material. Generated audio gets one retry of the trusted server instruction. Fresh generated audio still goes through server safety and Jev. Repeated failures end with `PRACTICE_OUTPUT_FAILED`; stop/rest and stale-slot cancellation take precedence. Legacy extensions retain their previous failure contract.

Deferred instructions wait for a pending input transcript to reach intent classification. Cancellation clears the deferred request and drain timer. The wait is bounded by the existing instruction timeout. PracticeFlowRouter forwards phase/substate to the per-output discard diagnostics.

Scenario correction now includes the rejected script as untrusted data, together with rejection reasons. Repairable missing-context/answer-leak/early-cue rejection can receive a final content attempt within the existing six-call/deadline budget. Every candidate still needs independent approval and server validation. Unsafe or uncertain scenes are not issued; generation can still fail safely.

## Verification (2026-10-23)

- Contract tests: 322; Core scenario/schema tests: 132; related realtime tests: 432.
- Regression coverage includes a 20ms next utterance after draining old output, pre-ACK first chunks, pending-input stop cancellation, mismatch followed by empty audio, stale failure events, bounded recovery, interruption, stop/rest, and guardrail rejection.
- Real GPT-Live synthetic capture: three approved lines (goal briefing, introduction, short peer line) were complete, matched their entire approved body, and passed server inspection. Audio sizes: 201600, 259200, 105600 bytes. No physical microphone/speaker verification is implied.
- Real synthetic scenario generation: two earlier requests were safely rejected; a diagnostic request repaired its rejected first candidate, received independent APPROVE plus server validation, and completed in 17874ms / four model calls. This does not guarantee every model request will pass safety review.
- Local comparison configuration remains `JEV_MODE=STUB`; no real Jev judgment is claimed. AWS Terraform and main are unchanged.

Protocol reference: https://developers.openai.com/api/docs/guides/live-conversations
