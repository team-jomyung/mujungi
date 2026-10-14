import { fireEvent, render, screen } from "@testing-library/react";
import { isValidElement, type ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TimingProvider } from "@/shared/timing/TimingProvider";
import { TimingConfigError } from "@/shared/timing/timing";
import { useToast } from "@/shared/toast/ToastProvider";
import { DeviceApp } from "@/device/DeviceApp";
import { PCM_CAPTURE_PROCESSOR_NAME } from "@/device/audio/pcm-capture.worklet";
import { DevLogin } from "@/parent/screens/DevLogin";
import { Done17 } from "@/parent/screens/Done17";
import { Free08 } from "@/parent/screens/Free08";
import { Home04 } from "@/parent/screens/Home04";
import { Live12 } from "@/parent/screens/Live12";
import { Register05a } from "@/parent/screens/Register05a";
import { Practice18 } from "@/parent/screens/Practice18";
import { Records26 } from "@/parent/screens/Records26";
import RootLayout, { metadata as rootMetadata, viewport } from "@/app/layout";
import { GET as healthz, dynamic as healthzDynamic } from "@/app/healthz/route";
import { GET as workletRoute, dynamic as workletDynamic } from "@/app/device/pcm-capture-worklet/route";
import DevicePage from "@/app/device/page";
import ParentLayout from "@/app/parent/layout";
import ParentHomePage from "@/app/parent/page";
import ParentDevicePage from "@/app/parent/device/page";
import { Device05 } from "@/parent/screens/Device05";
import ParentRegisterPage from "@/app/parent/device/register/page";
import ParentFreePage from "@/app/parent/free/page";
import ParentDevLoginPage from "@/app/parent/dev-login/page";
import ParentPracticePage from "@/app/parent/practice/page";
import ParentRecordsPage from "@/app/parent/records/page";
import ParentLivePage from "@/app/parent/sessions/[sessionId]/page";
import ParentDonePage from "@/app/parent/sessions/[sessionId]/done/page";
import { SESSION_ID, testTiming } from "../support/fixtures";

// 라우트 시험 (U1 3.5 Step 41 · J119 · J57 · BR8.4 · NFR6.1 · SD-14)
// 서버 컴포넌트 · 라우트 처리기 함수를 직접 부른다. redirect · notFound는 실제 next/navigation이 던지는 신호(digest)로 확인하고,
// 요청 범위 밖에서 쓸 수 없는 connection()만 대역으로 바꾼다.

const connectionMock = vi.hoisted(() => vi.fn(async () => undefined));

vi.mock("next/server", async (importOriginal) => ({
  ...(await importOriginal<typeof import("next/server")>()),
  connection: connectionMock,
}));

const NOT_FOUND_DIGEST = "NEXT_HTTP_ERROR_FALLBACK;404";

async function asyncDigestOf(run: () => Promise<unknown>): Promise<string | null> {
  try {
    await run();
  } catch (error) {
    return typeof error === "object" && error !== null ? String((error as { digest?: unknown }).digest) : null;
  }
  return null;
}

function elementOf(value: unknown): ReactElement<Record<string, unknown>> {
  if (!isValidElement<Record<string, unknown>>(value)) {
    throw new Error("React 요소가 아니다");
  }
  return value;
}

beforeEach(() => {
  connectionMock.mockClear();
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("라우트 처리기", () => {
  it("/healthz는 다른 서비스를 부르지 않고 200 {status: UP} · no-store다", async () => {
    const response = healthz();
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(await response.json()).toEqual({ status: "UP" });
    expect(healthzDynamic).toBe("force-dynamic");
  });

  it("/device/pcm-capture-worklet은 같은 origin JavaScript로 처리기 코드를 낸다(J121 · J138)", async () => {
    const response = workletRoute();
    expect(response.headers.get("Content-Type")).toBe("text/javascript; charset=utf-8");
    expect(response.headers.get("X-Content-Type-Options")).toBe("nosniff");
    const source = await response.text();
    expect(source).toContain(`registerProcessor(${JSON.stringify(PCM_CAPTURE_PROCESSOR_NAME)}`);
    expect(source).toContain("this.port.postMessage");
    expect(workletDynamic).toBe("force-static");
  });
});

describe("스텁 라우트 → /parent(J57 · J119)", () => {
  it("/parent/records는 U6의 실제 26 화면을 연결한다", () => {
    expect(elementOf(elementOf(ParentRecordsPage()).props.children).type).toBe(Records26);
  });

  it("/parent/device는 U2가 스텁 대신 05 화면을 그린다(J57 대체)", () => {
    expect(elementOf(ParentDevicePage()).type).toBe(Device05);
  });
});

describe("/parent/dev-login — MUJOONG_DEVLOGIN_ENABLED가 정확히 true일 때만(J119 · SD-14)", () => {
  it("환경 변수가 true면 시험 로그인 화면을 낸다", async () => {
    vi.stubEnv("MUJOONG_DEVLOGIN_ENABLED", "true");
    const element = elementOf(await ParentDevLoginPage());
    expect(element.type).toBe(DevLogin);
    expect(connectionMock).toHaveBeenCalledTimes(1);
  });

  it.each([undefined, "", "false", "TRUE", "1", " true"])("환경 변수가 %j이면 없는 화면(404)이다", async (value) => {
    vi.stubEnv("MUJOONG_DEVLOGIN_ENABLED", value);
    expect(await asyncDigestOf(() => ParentDevLoginPage())).toBe(NOT_FOUND_DIGEST);
  });
});

describe("화면 라우트", () => {
  it("/parent/practice는 U5a의 실제 18 화면을 연결한다", async () => {
    expect(elementOf(await ParentPracticePage()).type).toBe(Practice18);
  });
  it("/parent · /parent/device/register는 04 · 05a를 그린다", () => {
    expect(elementOf(ParentHomePage()).type).toBe(Home04);
    expect(elementOf(ParentRegisterPage()).type).toBe(Register05a);
  });

  it("/parent/free?failed=1이면 08이 사유 카드로 시작하고 그 밖에는 빈 화면이다(J120)", async () => {
    const failed = elementOf(await ParentFreePage({ searchParams: Promise.resolve({ failed: "1" }) }));
    expect(failed.type).toBe(Free08);
    expect(failed.props.returnedFailed).toBe(true);
    const plain = elementOf(await ParentFreePage({ searchParams: Promise.resolve({}) }));
    expect(plain.props.returnedFailed).toBe(false);
    const repeated = elementOf(await ParentFreePage({ searchParams: Promise.resolve({ failed: ["1", "1"] }) }));
    expect(repeated.props.returnedFailed).toBe(false);
  });

  it("/parent/sessions/[id] · /done은 UUID일 때만 12 · 17을 그린다", async () => {
    const live = elementOf(await ParentLivePage({ params: Promise.resolve({ sessionId: SESSION_ID }) }));
    expect(live.type).toBe(Live12);
    expect(live.props.sessionId).toBe(SESSION_ID);
    const done = elementOf(await ParentDonePage({ params: Promise.resolve({ sessionId: SESSION_ID }) }));
    expect(done.type).toBe(Done17);
    expect(done.props.sessionId).toBe(SESSION_ID);
  });

  it.each(["abc", "../internal", `${SESSION_ID}x`, "00000000-0000-4000-8000-00000000002"])(
    "형식이 틀린 sessionId %j는 서버로 보내지 않고 404다",
    async (sessionId) => {
      expect(await asyncDigestOf(() => ParentLivePage({ params: Promise.resolve({ sessionId }) }))).toBe(NOT_FOUND_DIGEST);
      expect(await asyncDigestOf(() => ParentDonePage({ params: Promise.resolve({ sessionId }) }))).toBe(NOT_FOUND_DIGEST);
    },
  );

  it("/device?debug=1이면 상태 로그를 켜고 그 밖에는 끈다(BR9.6 · AC6.2.9)", async () => {
    const debug = elementOf(await DevicePage({ searchParams: Promise.resolve({ debug: "1" }) }));
    expect(debug.type).toBe(DeviceApp);
    expect(debug.props.debug).toBe(true);
    for (const params of [{}, { debug: "0" }, { debug: ["1"] }, { debug: "true" }]) {
      expect(elementOf(await DevicePage({ searchParams: Promise.resolve(params) })).props.debug).toBe(false);
    }
  });
});

describe("레이아웃", () => {
  it("루트 레이아웃은 lang=ko이고 요청마다 기동 환경 변수의 시간 값을 싣는다(NFR6.1 · LC-2)", async () => {
    vi.stubEnv("MUJOONG_TIMING_PARENT_POLLINTERVAL", "1500ms");
    const html = elementOf(await RootLayout({ children: "child" }));
    expect(html.type).toBe("html");
    expect(html.props.lang).toBe("ko");
    const body = elementOf(html.props.children);
    const provider = elementOf(body.props.children);
    expect(provider.type).toBe(TimingProvider);
    expect((provider.props.timing as { parentPollIntervalMs: number }).parentPollIntervalMs).toBe(1_500);
    expect(connectionMock).toHaveBeenCalledTimes(1);
    expect(rootMetadata.robots).toEqual({ index: false, follow: false });
    expect(viewport.width).toBe("device-width");
  });

  it("시간 환경 변수 형식이 틀리면 첫 응답을 만들지 않고 멈춘다(fail fast)", async () => {
    vi.stubEnv("MUJOONG_DEVICEWEB_RETRYINTERVAL", "five");
    await expect(RootLayout({ children: null })).rejects.toBeInstanceOf(TimingConfigError);
  });

  it("보호자 레이아웃은 토스트 · API 문맥을 두고 자식을 그린다", () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ code: "UNAUTHENTICATED" }), { status: 401 })),
    );
    function Probe() {
      const toast = useToast();
      return (
        <button type="button" data-testid="probe" onClick={() => toast.show({ message: "알림" })}>
          probe
        </button>
      );
    }
    render(<TimingProvider timing={testTiming()}>{ParentLayout({ children: <Probe /> })}</TimingProvider>);
    expect(screen.getByTestId("probe").closest(".mj-parent")).not.toBeNull();
    fireEvent.click(screen.getByTestId("probe"));
    expect(screen.getByTestId("toast")).toHaveTextContent("알림");
  });
});
