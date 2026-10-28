import type { GuardianSummary } from "../../data/types";
import { ADMIN_COPY as C } from "../../common/copy";
import { totalRecords } from "../../common/format";
import type { SelectedChild } from "./types";

export function GuardianDevices({ guardian, onChild }: {
  guardian: GuardianSummary;
  onChild: (child: SelectedChild) => void;
}) {
  return (
    <div id={`guardian-${guardian.id}`} className="mj-admin-device-grid">
      {guardian.devices.length ? guardian.devices.map((device) => (
        <section key={device.id} className="mj-admin-device-card">
          <h3>{device.name}</h3>
          <small>{device.id}</small>
          <ul>
            {device.children.length ? device.children.map((child) => (
              <li key={child.id}>
                <div><strong>{child.name}</strong><small>{totalRecords(child.activityCount)}</small></div>
                <button
                  type="button"
                  className="mj-admin-text-button"
                  onClick={() => onChild({ ...child, guardianId: guardian.id, deviceId: device.id })}
                >
                  {C.childActivities}{C.openArrow}
                </button>
              </li>
            )) : <li>{C.noChildren}</li>}
          </ul>
        </section>
      )) : <p>{C.noDevices}</p>}
    </div>
  );
}
