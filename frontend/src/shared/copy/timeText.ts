// 화면 시각 표기 (U1 3.5 Step 37 · BR8.10 · interaction-spec 1절 '날짜 · 시각')
// 서버 시각은 ISO 8601 UTC다. 화면은 Asia/Seoul의 24시간 'hh:mm'으로 보인다.

const DISPLAY_TIME_ZONE = "Asia/Seoul";
const MS_PER_MINUTE = 60_000;

const HHMM_FORMAT = new Intl.DateTimeFormat("en-GB", {
  timeZone: DISPLAY_TIME_ZONE,
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

function toEpochMs(iso: string | null | undefined): number | null {
  if (typeof iso !== "string" || iso.length === 0) {
    return null;
  }
  const epochMs = Date.parse(iso);
  return Number.isNaN(epochMs) ? null : epochMs;
}

/** '10:32' 꼴. 없거나 읽을 수 없으면 null. */
export function formatHhmm(iso: string | null | undefined): string | null {
  const epochMs = toEpochMs(iso);
  if (epochMs === null) {
    return null;
  }
  const parts = HHMM_FORMAT.formatToParts(new Date(epochMs));
  const hour = parts.find((part) => part.type === "hour")?.value;
  const minute = parts.find((part) => part.type === "minute")?.value;
  return hour === undefined || minute === undefined ? null : `${hour}:${minute}`;
}

/** 두 시각 사이의 분(반올림, 0 이상). 하나라도 읽을 수 없으면 null. */
export function minutesBetween(fromIso: string | null | undefined, toIso: string | null | undefined): number | null {
  const from = toEpochMs(fromIso);
  const to = toEpochMs(toIso);
  if (from === null || to === null) {
    return null;
  }
  return Math.max(0, Math.round((to - from) / MS_PER_MINUTE));
}

/** 'n분째'의 n(첫 1분 안이면 1). 하나라도 읽을 수 없으면 null. */
export function elapsedMinuteOrdinal(startIso: string | null | undefined, nowIso: string | null | undefined): number | null {
  const start = toEpochMs(startIso);
  const now = toEpochMs(nowIso);
  if (start === null || now === null) {
    return null;
  }
  return Math.max(0, Math.floor((now - start) / MS_PER_MINUTE)) + 1;
}
