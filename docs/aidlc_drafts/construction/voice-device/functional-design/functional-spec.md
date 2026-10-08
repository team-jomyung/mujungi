# functional-spec.md (초안) · Unit `voice-device` · 아이 기기 웹 (/device) · 음성 중계 · 게이트

> 초안. 화면 설계 v1.5j(2026-10-08)와 개발 명세 1008에서 뽑았다. AI-DLC Edit File 모드에서 붙여넣기용이며 번호 · 절 구성은 AI-DLC가 다시 매긴다. 화면 설계가 기준이다.


## D-00 · D0 아이 기기 웹 · 처음 켜기
아이 기기 웹 첫 화면. 서버가 발급한 코드를 보여 주고 등록을 기다린다.

진입 조건: 

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| TXT-CODE | 글 | 코드 8자 |  |
| TXT-REGISTERED | 글 | 등록되었어요 (3초) | → T19 대기 |

**API**
- `POST /api/device/bootstrap {deviceSecret?} → {deviceSecret, code, status}`
- `GET /api/device/bootstrap/status → {status: UNREGISTERED|REGISTERED, deviceToken?}`

**오류**
| 상황 | 처리 |
|---|---|
| 서버 연결 실패 | '연결을 확인하는 중' 표시 · 재시도 |

## D-19 · T19 아이 기기 웹 · 상태 화면
아이 기기 상태 화면. 꺼짐 → 켜기(마이크 허용 · 연결) → 대기 · 듣는 중 · 생각 중 · 말하는 중 · 마무리 · 연결 끊김. 클릭 요소는 켜기 · 끄기뿐.

진입 조건: DEVICE_TOKEN

**요소와 동작**
| 요소 ID | 종류 | 표시 | 누르면 |
|---|---|---|---|
| BTN-ON | 단추 | 켜기 | getUserMedia → WS 연결 → 대기 |
| BTN-OFF | 단추 | 끄기 | WS 닫음 → 꺼짐 (보호자에게는 연결 끊김) |
| VIEW-STATE | 그림 | 상태 표정 · 색 |  |

**API**
- `WS /ws/device?token= ← state{IDLE|LISTENING|THINKING|SPEAKING|CLOSING|DISCONNECTED}, audio ↔, heartbeat 5초`
- `POST /api/device/heartbeat (WS 없을 때)`

**오류**
| 상황 | 처리 |
|---|---|
| 마이크 거부 | '마이크를 허용해 주세요' + 「켜기」 다시 |
| 연결 끊김 | '다시 연결하고 있어요 (n번째)' · 지수 백오프 · 40초 넘으면 보호자 P2 |
