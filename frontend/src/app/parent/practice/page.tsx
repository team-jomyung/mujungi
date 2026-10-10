import { redirect } from "next/navigation";

// /parent/practice — 18 연습 스텁(U1 3.5 Step 40 · J119). U5가 화면을 만들기 전까지 04로 되돌린다.
export default function ParentPracticeStubPage(): never {
  redirect("/parent");
}
