/** 관리자 화면과 제안 조회 계약의 날짜는 한국 시각을 사용한다. */
/** 달력과 차트의 화면 상한이다. 서버 조회 상한이나 시간 설정 계약은 아니다. */
export const MAX_RANGE_DAYS = 366;
export function koreaDate(now: Date = new Date()): string {
  return new Date(now.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

export function shiftDate(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

export function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function validRange(from: string, to: string, today = koreaDate()): boolean {
  if (!validDate(from) || !validDate(to) || from > to || to > today)
    return false;
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000 + 1;
  return days <= MAX_RANGE_DAYS;
}

export function dateInRange(timestamp: string, from: string, to: string): boolean {
  const date = koreaDate(new Date(timestamp));
  return date >= from && date <= to;
}

export function recentRange(days: number, today = koreaDate()): {
  from: string;
  to: string;
} {
  return {
    from: shiftDate(today, 1 - days),
    to: today
  };
}
