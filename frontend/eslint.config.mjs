// 무중 웹 ESLint flat config (U1 3.5 Step 4 · NFR11.1 · PD-15 · SD-11 · NFR10.4 · PD-16)
// - 영역 경계: src/{parent,device,admin}은 서로 import하지 않고 src/shared는 세 영역을 import하지 않는다(각 app 라우트 포함).
//   import 문자열 검사(no-restricted-imports)와 해석된 경로 검사(import/no-restricted-paths)를 함께 건다.
// - 보호자 안쪽: src/parent/common ↛ src/parent/features (의존은 기능 → 공통 한 방향), 기능 폴더끼리도 서로 import하지 않는다.
// - 관리자 안쪽: common · data는 features를 import하지 않고, 관리자 기능 폴더끼리도 서로 import하지 않는다.
// - react/no-danger 오류(SD-11), 화면 문구는 copy 모듈에서만(react/jsx-no-literals).
// - getUserMedia는 src/device/mic/micPermission.ts(「켜기」 클릭 핸들러에서 동적 import)에서만 부른다(NFR10.4 · PD-16).
// - 기기·공용의 서비스 워커는 금지. U7 부모 전용 등록 모듈 한 곳만 예외(U7-J9).
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";

const DEVICE_AREAS = ["./src/device", "./src/app/device"];
const PARENT_AREAS = ["./src/parent", "./src/app/parent"];
const ADMIN_AREAS = ["./src/admin", "./src/app/admin"];

const DEVICE_IMPORT_PATTERNS = [
  {
    regex: "^@/(app/)?device(/|$)",
    message: "보호자 · 관리자 영역과 공용 계층은 기기 영역을 import하지 않는다(NFR11.1 · PD-15).",
  },
  {
    regex: "^(\\.\\./)+(app/)?device(/|$)",
    message: "보호자 · 관리자 영역과 공용 계층은 기기 영역을 import하지 않는다(NFR11.1 · PD-15).",
  },
];

const PARENT_IMPORT_PATTERNS = [
  {
    regex: "^@/(app/)?parent(/|$)",
    message: "기기 · 관리자 영역과 공용 계층은 보호자 영역을 import하지 않는다(PD-15).",
  },
  {
    regex: "^(\\.\\./)+(app/)?parent(/|$)",
    message: "기기 · 관리자 영역과 공용 계층은 보호자 영역을 import하지 않는다(PD-15).",
  },
];

const ADMIN_IMPORT_PATTERNS = [
  {
    regex: "^@/(app/)?admin(/|$)",
    message: "보호자 · 기기 영역과 공용 계층은 관리자 영역을 import하지 않는다(JIRA-279).",
  },
  {
    regex: "^(\\.\\./)+(app/)?admin(/|$)",
    message: "보호자 · 기기 영역과 공용 계층은 관리자 영역을 import하지 않는다(JIRA-279).",
  },
];

const COMMON_IMPORT_PATTERNS = [
  {
    regex: "^@/parent/features(/|$)",
    message: "보호자 공통(parent/common)은 기능 폴더(parent/features)를 import하지 않는다.",
  },
  {
    regex: "^(\\.\\./)+features(/|$)",
    message: "보호자 공통(parent/common)은 기능 폴더(parent/features)를 import하지 않는다.",
  },
];

const ADMIN_FEATURE_IMPORT_PATTERNS = [
  {
    regex: "^@/admin/features(/|$)",
    message: "관리자 공통 · 데이터 계층은 기능 폴더(admin/features)를 import하지 않는다.",
  },
  {
    regex: "^(\\.\\./)+(admin/)?features(/|$)",
    message: "관리자 공통 · 데이터 계층은 기능 폴더(admin/features)를 import하지 않는다.",
  },
];

// 보호자 기능 폴더(src/parent/features/<기능>). 한 기능은 다른 기능을 import하지 않는다. 둘 이상의 기능이 쓰면 parent/common으로 둔다.
const FEATURES = ["child", "consent", "device", "done", "free", "home", "live", "login", "practice", "records", "reports"];
const featureDir = (name) => `./src/parent/features/${name}`;

const restrictedPaths =(targets, froms) =>
  targets.flatMap((target) => froms.map((from) => ({ target, from })));

const featureZones = FEATURES.flatMap((name) =>
  FEATURES.filter((other) => other !== name).map((other) => ({
    target: featureDir(name),
    from: featureDir(other),
    message: "한 기능 폴더는 다른 기능 폴더를 import하지 않는다. 둘 이상의 기능이 쓰면 parent/common으로 옮긴다.",
  })),
);

const featureImportPatterns = (name) => {
  const others = FEATURES.filter((other) => other !== name).join("|");
  const message = "한 기능 폴더는 다른 기능 폴더를 import하지 않는다. 둘 이상의 기능이 쓰면 parent/common으로 옮긴다.";
  return [
    { regex: `^@/parent/features/(${others})(/|$)`, message },
    { regex: `^(\\.\\./)+(features/)?(${others})(/|$)`, message },
  ];
};

const ADMIN_FEATURES = ["overview", "activity", "quality", "cost"];
const adminFeatureDir = (name) => `./src/admin/features/${name}`;
const adminFeatureZones = ADMIN_FEATURES.flatMap((name) =>
  ADMIN_FEATURES.filter((other) => other !== name).map((other) => ({
    target: adminFeatureDir(name),
    from: adminFeatureDir(other),
    message: "관리자 기능 폴더끼리는 import하지 않는다. 여러 기능이 쓰는 코드는 admin/common으로 옮긴다.",
  })),
);

const adminFeatureImportPatterns = (name) => {
  const others = ADMIN_FEATURES.filter((other) => other !== name).join("|");
  const message = "관리자 기능 폴더끼리는 import하지 않는다. 여러 기능이 쓰는 코드는 admin/common으로 옮긴다.";
  return [
    { regex: `^@/admin/features/(${others})(/|$)`, message },
    { regex: `^(\\.\\./)+(features/)?(${others})(/|$)`, message },
  ];
};

export default defineConfig([
  ...nextVitals,
  ...nextTypescript,
  globalIgnores([".next/**", "out/**", "build/**", "coverage/**", "next-env.d.ts"]),
  {
    files: ["src/**/*.{ts,tsx}"],
    rules: {
      "react/no-danger": "error",
      "no-restricted-syntax": [
        "error",
        {
          selector: "MemberExpression[property.name='getUserMedia'], MemberExpression[property.value='getUserMedia']",
          message: "getUserMedia는 src/device/mic/micPermission.ts에서만 부른다(NFR10.4 · PD-16).",
        },
        {
          selector: "MemberExpression[property.name='serviceWorker'], MemberExpression[property.value='serviceWorker']",
          message: "서비스 워커 · PWA를 쓰지 않는다(NFR10.4).",
        },
      ],
      "import/no-restricted-paths": [
        "error",
        {
          basePath: import.meta.dirname,
          zones: [
            ...restrictedPaths(PARENT_AREAS, DEVICE_AREAS),
            ...restrictedPaths(DEVICE_AREAS, PARENT_AREAS),
            ...restrictedPaths(ADMIN_AREAS, [...PARENT_AREAS, ...DEVICE_AREAS]),
            ...restrictedPaths([...PARENT_AREAS, ...DEVICE_AREAS], ADMIN_AREAS),
            ...restrictedPaths(["./src/shared"], [...DEVICE_AREAS, ...PARENT_AREAS, ...ADMIN_AREAS]),
            // 보호자 공통은 기능 폴더를 import하지 않는다(의존 방향은 기능 → 공통 한 방향).
            { target: "./src/parent/common", from: "./src/parent/features" },
            // 한 기능 폴더는 다른 기능 폴더를 모른다(기능 ↔ 기능 import 금지).
            ...featureZones,
            ...restrictedPaths(["./src/admin/common", "./src/admin/data"], ["./src/admin/features"]),
            ...adminFeatureZones,
          ],
        },
      ],
    },
  },
  {
    files: ["src/**/*.tsx"],
    rules: {
      "react/jsx-no-literals": ["error", { noStrings: true, ignoreProps: true, allowedStrings: [] }],
    },
  },
  {
    files: ["src/parent/**/*.{ts,tsx}", "src/app/parent/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": ["error", { patterns: [...DEVICE_IMPORT_PATTERNS, ...ADMIN_IMPORT_PATTERNS] }],
    },
  },
  {
    // 위 보호자 규칙을 덮어쓰므로 기기 · 관리자 영역 금지 패턴을 함께 적는다.
    files: ["src/parent/common/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": ["error", { patterns: [...DEVICE_IMPORT_PATTERNS, ...ADMIN_IMPORT_PATTERNS, ...COMMON_IMPORT_PATTERNS] }],
    },
  },
  // 기능 폴더마다: 위 보호자 규칙을 덮어쓰므로 기기 · 관리자 영역 금지 패턴을 함께 적는다.
  ...FEATURES.map((name) => ({
    files: [`src/parent/features/${name}/**/*.{ts,tsx}`],
    rules: {
      "no-restricted-imports": ["error", { patterns: [...DEVICE_IMPORT_PATTERNS, ...ADMIN_IMPORT_PATTERNS, ...featureImportPatterns(name)] }],
    },
  })),
  {
    files: ["src/device/**/*.{ts,tsx}", "src/app/device/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": ["error", { patterns: [...PARENT_IMPORT_PATTERNS, ...ADMIN_IMPORT_PATTERNS] }],
    },
  },
  {
    files: ["src/admin/**/*.{ts,tsx}", "src/app/admin/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": ["error", { patterns: [...DEVICE_IMPORT_PATTERNS, ...PARENT_IMPORT_PATTERNS] }],
    },
  },
  {
    // 관리자 전체 규칙을 덮어쓰므로 보호자 · 기기 영역 금지도 함께 유지한다.
    files: ["src/admin/common/**/*.{ts,tsx}", "src/admin/data/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": [
        "error",
        { patterns: [...DEVICE_IMPORT_PATTERNS, ...PARENT_IMPORT_PATTERNS, ...ADMIN_FEATURE_IMPORT_PATTERNS] },
      ],
    },
  },
  ...ADMIN_FEATURES.map((name) => ({
    files: [`src/admin/features/${name}/**/*.{ts,tsx}`],
    rules: {
      "no-restricted-imports": [
        "error",
        { patterns: [...DEVICE_IMPORT_PATTERNS, ...PARENT_IMPORT_PATTERNS, ...adminFeatureImportPatterns(name)] },
      ],
    },
  })),
  {
    files: ["src/shared/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-imports": ["error", { patterns: [...DEVICE_IMPORT_PATTERNS, ...PARENT_IMPORT_PATTERNS, ...ADMIN_IMPORT_PATTERNS] }],
    },
  },
  {
    files: ["src/parent/common/push/browserPush.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector: "MemberExpression[property.name='getUserMedia'], MemberExpression[property.value='getUserMedia']",
          message: "getUserMedia는 src/device/mic/micPermission.ts에서만 부른다(NFR10.4 · PD-16).",
        },
      ],
    },
  },
  {
    files: ["src/device/mic/micPermission.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector: "MemberExpression[property.name='serviceWorker'], MemberExpression[property.value='serviceWorker']",
          message: "서비스 워커 · PWA를 쓰지 않는다(NFR10.4).",
        },
      ],
    },
  },
]);
