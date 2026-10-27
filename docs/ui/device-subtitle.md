# 아이 기기 3D 무중이 · 게임식 대화창

## 3D 무중이 (`frontend/src/device/features/avatar/`)

- `DuckAvatar.tsx`가 `/models/Explorer_A_Web.meshopt.glb`(three.js 0.182, Meshopt 압축)를 불러와 T19 캐릭터 자리에 그린다. 준비 전 · 실패(WebGL 없음, 파일 없음) 때는 기존 도형 캐릭터가 그대로 보인다.
- GLB 안 그림은 blob: 주소 없이 바로 `createImageBitmap`으로 푼다. 기본 GLTFLoader 경로는 blob: URL을 fetch해 CSP `connect-src 'self'`에 막히기 때문이다. CSP는 넓히지 않았다.
- 상태 → 동작(`duckClips.ts`)

  | 기기 상태 | 클립 |
  |---|---|
  | 꺼짐 · 기본 | `V6_Breathe_WeightShift` |
  | 대기 | `Expressive_Wave` |
  | 듣는 중 · 생각 중 | `V6_HeadLook_Blink_Test` |
  | 말하는 중 | `Expressive_Talk` |
  | 연결 중 · 재연결 | `V6_SmallSteps_Prototype` |
  | 마무리 | `Happy_Laugh` |
  | 마이크 거부 | `Mild_Sad` |
  | 모션 줄이기 설정 | `V6_Neutral`(고정 자세) |

- 모델 파일은 `frontend/public/models/Explorer_A_Web.meshopt.glb`(16MB, 삼각형 약 53만)이고 `frontend/Dockerfile`이 웹 이미지에 넣는다. 저사양 기기 성능은 실기에서 확인해야 한다. 파일을 못 받으면 도형 캐릭터로 동작한다.

## 게임식 대화창 (`frontend/src/device/features/status/SubtitleDialog.tsx`)

| 상태 | 대화창 |
|---|---|
| 말하는 중 | 「무중이」 이름표 + 무중이 말을 한 글자씩(45ms), 다 쓰면 ▼ |
| 듣는 중 | 초록 창, 「내 차례」 + 「말해 볼까?」 |
| 생각 중 | 「음… 생각하고 있어」 + 점 세 개 |
| 그 밖 | 창을 내린다 |

- 글은 서버가 보내는 `OUTPUT_BEGIN.text`(선택, 500자 이하)다. 게이트를 통과한 그 말의 확정 글이며 부모 말풍선과 같은 출처(`sealed.transcript()`)다.
- 기기는 글을 재생 번호별로 두었다가 그 재생이 실제로 소리 나는 `STARTED` 때 띄우고, `STOPPED`(끼어들기 · 멈춤) · 말하기 끝 · 연결 끊김 · 끄기 때 지운다.
- 응답을 나눠 보내면(progressive) 묶음마다 그 사이 새로 통과한 글만 싣는다.
- 글은 화면에만 두고 기록 · 로그에 남기지 않는다(R-F14). `OutputBegin` · `OutputBundle`의 `toString`에는 글자 수만 쓴다.
- 모션 줄이기 설정이면 한 글자씩 쓰지 않고 한 번에 보인다. 화면 읽기는 다 쓴 문장을 한 번 읽는다.

## 남은 일

- 연습 목표 표현 강조(시안의 형광펜): 목표 표현을 서버가 따로 보내야 한다.
- `PLAY_APPROVED_CONTENT`(승인 음성) 자막: 서버는 `ApprovedItem.text`를 갖고 있으나 아직 보내지 않는다.
