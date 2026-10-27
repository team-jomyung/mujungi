import { ADMIN_COPY as C, timingLabel } from "./copy";

export function number(value: number | null): string {
  return value === null ? C.unknown : value.toLocaleString("ko-KR");
}

export function duration(seconds: number | null): string {
  if (seconds === null) return C.unknown;
  // 합계 초를 먼저 반올림해야 59.6초도 1분 0초가 된다.
  const rounded = Math.max(0, Math.round(seconds));
  const minutes = Math.floor(rounded / 60);
  return minutes ? `${minutes}분 ${rounded % 60}초` : `${rounded}초`;
}

const usdOptions = { style: "currency", currency: "USD", minimumFractionDigits: 2 } as const;
const usdCents = new Intl.NumberFormat("en-US", { ...usdOptions, maximumFractionDigits: 2 });
const usdPrecise = new Intl.NumberFormat("en-US", { ...usdOptions, maximumFractionDigits: 6 });

export function money(usd: number | null): string {
  if (usd === null) return C.unknown;
  const formatted = (usd >= 1 ? usdCents : usdPrecise).format(usd);
  // 이 비교는 표시 결과만 판별한다. 합산하거나 원본 금액을 반올림하지 않는다.
  return usd > 0 && formatted === usdPrecise.format(0) ? C.smallUsd : formatted;
}

export function dateTime(iso: string): string {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(iso));
}

export function clockTime(iso: string): string {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(iso));
}

export function responseTiming(value: string | null): string {
  return value === null ? C.noneRecorded : timingLabel[value] ?? value;
}

export function percent(value: number | null): string {
  return value === null ? C.unknown : `${(value * 100).toLocaleString("ko-KR", { maximumFractionDigits: 1 })}%`;
}

export const times = (value: number) => `${number(value)}회`;
export const totalRecords = (value: number) => `총 ${number(value)}건`;
export const childrenCount = (value: number) => `${number(value)}명`;
export const devicesCount = (value: number) => `${number(value)}대`;
export const pageLabel = (page: number, pages: number) => `${page + 1} / ${Math.max(1, pages)} 페이지`;
export const detailLabel = (name: string, id: string) => `${name} · ${id} ${C.detail}`;
export const rangeText = (from: string, to: string) => `${from.replaceAll("-", ".")} — ${to.replaceAll("-", ".")}`;
export const typeCountsText = (free: number, practice: number) => `자유대화 ${number(free)} · 연습대화 ${number(practice)}`;
export const pairText = (first: string, second: string) => `${first} / ${second}`;
export const countText = (value: number) => `${number(value)}건`;
export const unlinkedText = (cost: string) => `연결 미확인 ${cost}`;
export const unknownCostText = (value: number) => `${C.totalsHint} (미확인 ${number(value)}건)`;
