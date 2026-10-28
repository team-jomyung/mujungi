import { ADMIN_COPY as C, timingLabel } from "./copy";
import type { UsdAmount } from "../data/types";

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

const usdCents = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function money(usd: UsdAmount | null): string {
  if (usd === null) return C.unknown;
  const [integer, fraction = ""] = usd.split(".");
  // 서버의 소수 최대 8자리 문자열을 정수로 옮겨 표시 반올림의 정밀도를 유지한다.
  const units = BigInt(integer + fraction.padEnd(8, "0"));
  const digits = BigInt(integer) >= 1n ? 2 : 6;
  const roundingUnit = 10n ** BigInt(8 - digits);
  const rounded = (units + roundingUnit / 2n) / roundingUnit;
  if (units > 0n && rounded === 0n) return C.smallUsd;
  const scale = 10n ** BigInt(digits);
  const decimal = (rounded % scale).toString().padStart(digits, "0").replace(/0+$/, "").padEnd(2, "0");
  // 금액 합산은 하지 않고, 달러 기호와 자릿수 구분만 Intl에 맡긴다.
  return usdCents.formatToParts(rounded / scale)
    .map(part => part.type === "fraction" ? decimal : part.value)
    .join("");
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

export const times = (value: number | null) => value === null ? C.unknown : `${number(value)}회`;
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
export const qualityRecordsText = (shown: number, total: number, limit: number) =>
  `최근 ${number(shown)}건 / 전체 ${number(total)}건 · 최대 ${number(limit)}건`;
