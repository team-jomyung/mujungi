"use client";

import { useEffect, useRef } from "react";
import * as THREE from "three";
import { MeshoptDecoder } from "three/addons/libs/meshopt_decoder.module.js";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { DUCK_CLIPS, type DuckMotion } from "./duckClips";

// 3D 무중이(Explorer_A_Web 패키지 DuckAvatar를 기기 화면에 맞게 줄임).
// 불러오기 · WebGL이 실패하면 onStatus("error")만 알리고, 화면은 기본 도형 캐릭터를 그대로 둔다.
// 화면 밖 · 숨은 탭에서는 그리지 않는다. 해제 때 네트워크 · GPU 자원을 모두 돌려준다.
// 아이 기기의 OS 「동작 줄이기」가 켜져 있어도 기본으로는 무중이가 움직인다(팀 결정, docs/ui/device-subtitle.md).
// honorReducedMotion을 켜면 그 설정일 때 중립 자세로 멈춘다.

export const DUCK_MODEL_SRC = "/models/Explorer_A_Web.meshopt.glb";
const MAX_DPR = 1.5;
const CROSS_FADE_SEC = 0.25;
const MAX_FRAME_SEC = 0.05;

export interface DuckAvatarProps {
  readonly motion: DuckMotion;
  readonly src?: string;
  readonly onStatus?: (status: "ready" | "error") => void;
  /** OS 「동작 줄이기」를 따를지. 기본 false(항상 움직임). */
  readonly honorReducedMotion?: boolean;
}

function releaseModel(root: THREE.Object3D): void {
  root.traverse((object) => {
    const mesh = object as THREE.Mesh;
    if (mesh.isMesh) {
      mesh.geometry.dispose();
      for (const material of Array.isArray(mesh.material) ? mesh.material : [mesh.material]) {
        for (const value of Object.values(material)) {
          if (value instanceof THREE.Texture) {
            const image: unknown = value.source.data;
            value.dispose();
            if (typeof ImageBitmap !== "undefined" && image instanceof ImageBitmap) image.close();
          }
        }
        material.dispose();
      }
    }
    const skinned = object as THREE.SkinnedMesh;
    if (skinned.isSkinnedMesh) skinned.skeleton.dispose();
  });
}

interface ImageSourceParser {
  readonly json: { readonly images?: ReadonlyArray<{ readonly bufferView?: number; readonly mimeType?: string }> };
  readonly sourceCache: Record<number, Promise<THREE.Texture> | undefined>;
  getDependency(type: "bufferView", index: number): Promise<ArrayBuffer>;
  loadImageSource(sourceIndex: number, loader: unknown): Promise<THREE.Texture>;
}

/**
 * GLB 안 그림을 blob: 주소 없이 바로 ImageBitmap으로 푼다. GLTFLoader 기본 경로는 blob: URL을 fetch해
 * CSP connect-src 'self'(shared/security/csp.ts)에 막히므로, CSP를 넓히지 않고 이 기기 화면에서만 우회한다.
 */
function decodeImagesWithoutBlobUrl(parser: ImageSourceParser): void {
  const original = parser.loadImageSource.bind(parser);
  parser.loadImageSource = (sourceIndex, loader) => {
    const source = parser.json.images?.[sourceIndex];
    if (source?.bufferView === undefined || typeof createImageBitmap !== "function") {
      return original(sourceIndex, loader);
    }
    const cached = parser.sourceCache[sourceIndex];
    if (cached !== undefined) {
      return cached.then((texture) => texture.clone());
    }
    const pending = parser
      .getDependency("bufferView", source.bufferView)
      .then((bytes) => createImageBitmap(new Blob([bytes], { type: source.mimeType }), { premultiplyAlpha: "none", colorSpaceConversion: "none" }))
      .then((bitmap) => {
        const texture = new THREE.Texture(bitmap);
        texture.needsUpdate = true;
        texture.userData.mimeType = source.mimeType;
        return texture;
      });
    parser.sourceCache[sourceIndex] = pending;
    return pending;
  };
}

function addLights(scene: THREE.Scene): void {
  scene.add(new THREE.HemisphereLight(0xffffff, 0x7d746b, 2.5));
  const key = new THREE.DirectionalLight(0xffffff, 3.0);
  key.position.set(3, 5, 5);
  scene.add(key);
  const fill = new THREE.DirectionalLight(0xffffff, 1.0);
  fill.position.set(-3, 2, -2);
  scene.add(fill);
}

export function DuckAvatar({ motion, src = DUCK_MODEL_SRC, onStatus, honorReducedMotion = false }: DuckAvatarProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const playRef = useRef<(motion: DuckMotion) => void>(() => undefined);
  const motionRef = useRef(motion);
  const statusRef = useRef(onStatus);

  useEffect(() => {
    motionRef.current = motion;
    statusRef.current = onStatus;
  });

  useEffect(() => {
    playRef.current(motion);
  }, [motion]);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;
    if (typeof window.matchMedia !== "function" || typeof ResizeObserver === "undefined" || typeof IntersectionObserver === "undefined") {
      statusRef.current?.("error");
      return;
    }
    let disposed = false;
    let frame = 0;
    let previousTime = 0;
    let inViewport = true;
    let model: THREE.Group | undefined;
    let mixer: THREE.AnimationMixer | undefined;
    let active: THREE.AnimationAction | undefined;
    let modelSize: THREE.Vector3 | undefined;
    const abort = new AbortController();
    const motionMedia = window.matchMedia("(prefers-reduced-motion: reduce)");
    const actions = new Map<DuckMotion, THREE.AnimationAction>();
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(35, 1, 0.01, 100);
    const holder = new THREE.Group();
    scene.add(holder);
    addLights(scene);

    const fail = () => {
      if (!disposed) statusRef.current?.("error");
    };

    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: "default" });
    } catch {
      fail();
      return;
    }
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.domElement.className = "mj-duck__canvas";
    host.appendChild(renderer.domElement);

    const resize = () => {
      const width = Math.max(1, host.clientWidth);
      const height = Math.max(1, host.clientHeight);
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAX_DPR));
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      if (modelSize !== undefined) {
        const vertical = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
        const distance = Math.max(modelSize.y / vertical, modelSize.x / (vertical * camera.aspect)) * 0.6 + modelSize.z * 0.5;
        camera.position.set(0, modelSize.y * 0.53, distance);
        camera.near = Math.max(0.005, distance / 100);
        camera.far = distance * 20;
        camera.lookAt(0, modelSize.y * 0.5, 0);
      }
      camera.updateProjectionMatrix();
    };

    const play = (wanted: DuckMotion) => {
      if (mixer === undefined) return;
      const reduce = honorReducedMotion && motionMedia.matches;
      const next = actions.get(reduce ? "neutral" : wanted) ?? actions.get("idle");
      if (next === undefined) return;
      if (reduce) mixer.stopAllAction();
      next.reset().setEffectiveTimeScale(1).setEffectiveWeight(1).play();
      if (!reduce && active !== undefined && active !== next) active.crossFadeTo(next, CROSS_FADE_SEC, false);
      active = next;
      if (reduce) {
        mixer.update(0);
        renderer.render(scene, camera);
      }
    };

    const animate = (now: number) => {
      if (disposed) return;
      const delta = previousTime === 0 ? 0 : Math.min((now - previousTime) / 1000, MAX_FRAME_SEC);
      previousTime = now;
      if (inViewport && !document.hidden && modelSize !== undefined) {
        if (!(honorReducedMotion && motionMedia.matches)) mixer?.update(delta);
        renderer.render(scene, camera);
      }
      frame = requestAnimationFrame(animate);
    };

    const onReducedMotion = () => play(motionRef.current);
    const onContextLost = (event: Event) => {
      event.preventDefault();
      fail();
    };
    const resizeObserver = new ResizeObserver(resize);
    const intersectionObserver = new IntersectionObserver(([entry]) => {
      inViewport = entry?.isIntersecting ?? true;
    });
    resizeObserver.observe(host);
    intersectionObserver.observe(host);
    motionMedia.addEventListener("change", onReducedMotion);
    renderer.domElement.addEventListener("webglcontextlost", onContextLost);
    resize();
    frame = requestAnimationFrame(animate);

    // 받기는 해제 때 끊고, 끊을 수 없는 해석은 늦게 끝나면 버린다(StrictMode 두 번 실행에도 안전).
    void (async () => {
      const url = new URL(src, window.location.href);
      const response = await fetch(url, { signal: abort.signal });
      if (!response.ok) throw new Error(`model ${response.status}`);
      const bytes = await response.arrayBuffer();
      if (disposed) return;
      const loader = new GLTFLoader();
      loader.setMeshoptDecoder(MeshoptDecoder);
      loader.register((parser) => {
        decodeImagesWithoutBlobUrl(parser as unknown as ImageSourceParser);
        return { name: "MJ_blobless_images" };
      });
      const gltf = await loader.parseAsync(bytes, new URL(".", url).href);
      if (disposed) {
        releaseModel(gltf.scene);
        return;
      }
      model = gltf.scene;
      holder.add(model);
      model.updateMatrixWorld(true);
      const box = new THREE.Box3().setFromObject(model);
      const center = box.getCenter(new THREE.Vector3());
      holder.position.set(-center.x, -box.min.y, -center.z);
      modelSize = box.getSize(new THREE.Vector3());
      resize();
      mixer = new THREE.AnimationMixer(model);
      for (const [name, clipName] of Object.entries(DUCK_CLIPS)) {
        const clip = THREE.AnimationClip.findByName(gltf.animations, clipName);
        if (clip !== null) actions.set(name as DuckMotion, mixer.clipAction(clip));
      }
      if (!actions.has("neutral") || !actions.has("idle")) throw new Error("model clips");
      playRef.current = play;
      play(motionRef.current);
      statusRef.current?.("ready");
    })().catch((reason: unknown) => {
      if (!(reason instanceof DOMException && reason.name === "AbortError")) fail();
    });

    return () => {
      disposed = true;
      abort.abort();
      cancelAnimationFrame(frame);
      playRef.current = () => undefined;
      resizeObserver.disconnect();
      intersectionObserver.disconnect();
      motionMedia.removeEventListener("change", onReducedMotion);
      renderer.domElement.removeEventListener("webglcontextlost", onContextLost);
      mixer?.stopAllAction();
      if (model !== undefined) {
        mixer?.uncacheRoot(model);
        releaseModel(model);
        holder.remove(model);
      }
      renderer.dispose();
      renderer.forceContextLoss();
      renderer.domElement.remove();
      scene.clear();
    };
  }, [src, honorReducedMotion]);

  return <div ref={hostRef} className="mj-duck" aria-hidden="true" />;
}
