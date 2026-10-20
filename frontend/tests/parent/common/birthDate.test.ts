import { describe, expect, it } from "vitest";
import { ageOnDate, birthDateOf, birthYears, daysInMonth, isLeapYear } from "@/parent/common/birthDate";
describe("생년월일", () => {
  it("윤년과 존재하지 않는 날짜를 확인한다", () => {
    expect(isLeapYear(2000)).toBe(true); expect(isLeapYear(1900)).toBe(false);
    expect(daysInMonth(2024,2)).toBe(29); expect(daysInMonth(2025,2)).toBe(28);
    expect(birthDateOf("2016","2","29")).toBe("2016-02-29");
    for (const parts of [["2017","2","29"],["2018","4","31"],["2018","13","1"],["2018","1","0"],["","1","1"]]) expect(birthDateOf(...parts as [string,string,string])).toBeNull();
  });
  it("생일 경계와 평년 3월 1일을 사용한다", () => {
    expect(ageOnDate("2012-02-29","2025-02-28")).toBe(12);
    expect(ageOnDate("2012-02-29","2025-03-01")).toBe(13);
    expect(ageOnDate("2013-10-21","2026-10-20")).toBe(12);
    expect(ageOnDate("2013-10-20","2026-10-20")).toBe(13);
    expect(birthYears("2026-10-20")).toEqual([2020,2019,2018,2017,2016,2015,2014,2013]);
  });
});
