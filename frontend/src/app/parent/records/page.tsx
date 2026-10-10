import { redirect } from "next/navigation";

// /parent/records — 26 기록 스텁(U1 3.5 Step 40 · J119). U6가 화면을 만들기 전까지 04로 되돌린다.
export default function ParentRecordsStubPage(): never {
  redirect("/parent");
}
