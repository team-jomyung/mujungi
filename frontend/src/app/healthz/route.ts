// web 컨테이너 헬스체크용 가벼운 라우트 (U1 3.5 Step 4 · infra-spec 2.1 · 2.3)
// 다른 서비스를 부르지 않고 프로세스가 요청을 받는지만 알린다. 캐시하지 않는다.
export const dynamic = "force-dynamic";

export function GET(): Response {
  return Response.json({ status: "UP" }, { headers: { "Cache-Control": "no-store" } });
}
