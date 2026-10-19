import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fireEvent, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { metadata as rootMetadata } from "@/app/layout";
import { flush, registeredDeviceApi, renderDeviceApp } from "../support/deviceHarness";
import { codeOf, frontendRoot, relative, sourceFiles } from "../support/sourceScan";

// 서비스 워커 · PWA 없음 시험 (U1 3.5 Step 41 · NFR10.4 · R4-16)
// 정적 검사: 소스 · 설정 · 정적 파일 · 의존성에 서비스 워커 등록과 PWA 매니페스트가 없다.
// 실행 확인: /device 착지와 「켜기」 동안 navigator.serviceWorker.register 호출이 0건이다.

describe("서비스 워커 · PWA 정적 검사", () => {
  it("U7 보호자 전용 등록 모듈 밖에는 serviceWorker 사용 · register 호출이 없다", () => {
    const offenders = sourceFiles(path.join(frontendRoot, "src"))
      .filter((file) => relative(file) !== "src/parent/common/push/browserPush.ts")
      .filter((file) => /serviceWorker|\.register\s*\(\s*["'`][^"'`]*sw[^"'`]*\.js/.test(codeOf(file)))
      .map(relative);
    expect(offenders).toEqual([]);
  });

  it("U7의 좁은 예외는 부모 scope만 등록하고 fetch·캐시·PWA를 만들지 않는다", () => {
    const registration = codeOf(path.join(frontendRoot, "src/parent/common/push/browserPush.ts"));
    expect(registration).toContain('const WORKER_SCOPE = "/parent/"');
    expect(registration).toContain('const WORKER_PATH = "/parent/push-worker"');
    const worker = codeOf(path.join(frontendRoot, "src/parent/common/push/pushWorker.ts"));
    expect(worker).not.toMatch(/addEventListener\(["']fetch|caches\.|CacheStorage|importScripts|workbox/i);
  });

  it("정적 파일에 서비스 워커 스크립트 · 웹 앱 매니페스트가 없다", () => {
    const publicDir = path.join(frontendRoot, "public");
    const publicFiles = existsSync(publicDir) ? readdirSync(publicDir, { recursive: true }).map(String) : [];
    expect(publicFiles.filter((file) => /(^|\/)(sw|service-worker|workbox-[^/]*)\.js$|manifest\.(json|webmanifest)$/.test(file))).toEqual([]);
    const appFiles = readdirSync(path.join(frontendRoot, "src/app"), { recursive: true }).map(String);
    expect(appFiles.filter((file) => /(^|\/)manifest\.(ts|json|webmanifest)$/.test(file))).toEqual([]);
  });

  it("빌드 설정 · 의존성에 PWA 플러그인이 없다", () => {
    const nextConfig = readFileSync(path.join(frontendRoot, "next.config.ts"), "utf8");
    expect(nextConfig).not.toMatch(/withPWA|next-pwa|workbox/i);
    const packageJson = JSON.parse(readFileSync(path.join(frontendRoot, "package.json"), "utf8")) as {
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const names = [...Object.keys(packageJson.dependencies ?? {}), ...Object.keys(packageJson.devDependencies ?? {})];
    expect(names.filter((name) => /pwa|workbox|service-worker/i.test(name))).toEqual([]);
  });

  it("루트 레이아웃 메타데이터가 웹 앱 매니페스트 · 홈 화면 앱 설정을 내지 않는다", () => {
    // Next.js는 metadata.manifest · appleWebApp으로 <link rel="manifest"> · apple-mobile-web-app-* 태그를 만든다.
    expect(rootMetadata).not.toHaveProperty("manifest");
    expect(rootMetadata).not.toHaveProperty("appleWebApp");
    const layoutCode = codeOf(path.join(frontendRoot, "src/app/layout.tsx"));
    expect(layoutCode).not.toMatch(/rel=["']manifest["']|apple-mobile-web-app/);
  });
});

describe("서비스 워커 실행 확인", () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, "serviceWorker");
    vi.useRealTimers();
  });

  it("/device 착지 · 유휴 미리 받기 · 「켜기」 동안 register가 0건이다", async () => {
    await import("@/device/mic/micPermission");
    await import("@/device/runtime/deviceRuntime");
    vi.useFakeTimers();
    const register = vi.fn();
    Object.defineProperty(navigator, "serviceWorker", { configurable: true, value: { register } });
    renderDeviceApp(registeredDeviceApi());
    await flush();
    await vi.runOnlyPendingTimersAsync();
    fireEvent.click(screen.getByTestId("t19-on"));
    await flush();
    expect(register).not.toHaveBeenCalled();
  });
});
