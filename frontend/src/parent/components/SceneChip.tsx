import { sceneLabel } from "@/shared/copy/result";
import type { Scene } from "../api/results";
export function SceneChip({ scene, testId }: { scene: Scene; testId: string }) { return <span className="mj-chip" data-testid={testId} data-chip={scene.sceneChip}>{sceneLabel(scene.sceneNo, scene.chipText)}</span>; }
