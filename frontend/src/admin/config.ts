export type AdminMode = "preview" | "api" | "disabled";
/** 서버 레이아웃에서 환경 설정을 읽는다. 운영 모드에서는 합성 미리보기를 허용하지 않는다. */
export function readAdminMode(env: Record<string, string | undefined>): AdminMode {
  if (env.NODE_ENV === "development" && env.MUJOONG_ADMIN_PREVIEW === "true") return "preview";
  return env.MUJOONG_ADMIN_API_ENABLED === "true" ? "api" : "disabled";
}
