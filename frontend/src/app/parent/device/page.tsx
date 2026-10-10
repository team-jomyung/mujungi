import { redirect } from "next/navigation";

// /parent/device — 05 우리 집 기기 스텁(U1 3.5 Step 40 · J57). U2가 두 줄 + 판정 카드 화면으로 바꾼다.
// 규칙(BR2.7 · BR2.8 · BR4.5 · BR4.6)은 '05로 간다'를 그대로 두고, U1에서는 이 스텁이 04로 되돌린다.
export default function ParentDeviceStubPage(): never {
  redirect("/parent");
}
