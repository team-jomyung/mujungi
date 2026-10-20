/** 서버와 같은 한국 날짜·평년 3/1 윤년 생일 기준. */
export function isLeapYear(year: number): boolean { return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0); }
export function daysInMonth(year: number, month: number): number {
  return month === 2 ? (isLeapYear(year) ? 29 : 28) : [4, 6, 9, 11].includes(month) ? 30 : 31;
}
export function birthDateOf(year: string, month: string, day: string): string | null {
  if (!/^\d{4}$/.test(year) || !/^\d{1,2}$/.test(month) || !/^\d{1,2}$/.test(day)) return null;
  const y = Number(year), m = Number(month), d = Number(day);
  if (y < 1 || m < 1 || m > 12 || d < 1 || d > daysInMonth(y, m)) return null;
  return `${year}-${month.padStart(2, "0")}-${day.padStart(2, "0")}`;
}
export function ageOnDate(birthDate: string, date: string): number {
  const [y, m, d] = birthDate.split("-").map(Number), year = Number(date.slice(0, 4));
  const birthday = m === 2 && d === 29 && !isLeapYear(year) ? `${year}-03-01` : `${year}${birthDate.slice(4)}`;
  return year - y - (date < birthday ? 1 : 0);
}
export function birthYears(registeredOn: string): number[] {
  const year = Number(registeredOn.slice(0, 4));
  return Array.from({ length: 8 }, (_, i) => year - 6 - i);
}
