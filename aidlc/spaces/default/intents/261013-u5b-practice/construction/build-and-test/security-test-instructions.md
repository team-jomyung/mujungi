# U5b 정보 분리·보안 검증 지침

## 이번 검증

새 공개 API/인증 연결은 이번 독립 구현에 없다. integration-test-instructions.md의 U5b 전체 시험과 기존 U1·U3·U4a·U5a 회귀를 중복 없이 사용한다. StageOutputPolicyTest·PracticeRecordProjectorTest·PracticeRepositoryIT·PracticeDeletionIT와 프런트 진행 시험에서 최소 정보, 다른 목표 참조 거절, 중복·삭제 후 늦은 갱신 거절, 본문/판정 미노출을 검사한다. 기존 ESLint와 TypeScript 검사도 수행한다.

## 한계와 실제 경계

기대 출력 연속 5어절 일치 0건은 요구사항의 검증 목표다. 현재 StageOutputPolicyTest는 containsAnswerSentence 등의 입력 사실과 DTO 필드 분리를 검사하며 5어절 비교 자체를 실행하지 않는다. 따라서 이 수치와 정식 생성물/평가자 승인은 Unverified다. 허용 DTO 검사도 실제 음성 공급자 프롬프트 승인·실제 생성물 안전성의 증거가 아니다. 실제 API 소유권·인증/로그/전사 비노출 및 브라우저/보조기기·44px 조작은 실제 통합에서 재검증해야 한다. 이번에는 배포 서비스에 대한 DAST나 CVE 데이터베이스 검사를 실행했다고 주장하지 않는다. 임의의 보안 면제·토큰/원문 로그 출력은 금지한다.

