"""Measure practice output latency from local Docker events without printing transcript or credentials."""
import argparse
from datetime import datetime
import json
import re
import subprocess


def summarize(lines):
    rows = {}
    for line in lines:
        try:
            event = json.loads(line)
            attrs = event.get("attrs", {})
            if event.get("event") != "PRACTICE_AUDIO_STAGE":
                continue
            stage = attrs.get("audioStage")
            if stage not in {"REQUEST_SENT", "COLLECTING", "PLAYBACK_QUEUED", "PLAYING", "PLAYBACK_ENDED"}:
                continue
            generation = attrs.get("outputGeneration")
            trace = event.get("traceId")
            if not isinstance(trace, str) or not re.fullmatch(r"[0-9a-fA-F]{16,32}", trace):
                continue
            if not isinstance(generation, int):
                continue
            stamp = datetime.fromisoformat(event["ts"].replace("Z", "+00:00"))
            key = (trace, generation)
            row = rows.setdefault(key, {})
            row.setdefault(stage, stamp)
            if stage == "REQUEST_SENT":
                for name in ("practicePhase", "practiceSub"):
                    value = attrs.get(name)
                    if isinstance(value, str) and re.fullmatch(r"[A-Z_]{1,40}", value):
                        row[name] = value
            if stage == "PLAYBACK_QUEUED":
                row["queued_units"] = row.get("queued_units", 0) + 1
        except (ValueError, TypeError, KeyError, AttributeError):
            continue
    result = []
    for (trace, generation), row in rows.items():
        request = row.get("REQUEST_SENT")
        if request is None:
            continue
        item = {"traceId": trace, "outputGeneration": generation, "requestAt": request.isoformat(),
                "queuedUnits": row.get("queued_units", 0)}
        item.update({name: row[name] for name in ("practicePhase", "practiceSub") if name in row})
        for stage, name in (("COLLECTING", "firstProviderOutputMs"), ("PLAYBACK_QUEUED", "firstQueuedMs"),
                            ("PLAYING", "firstPlayingMs")):
            stamp = row.get(stage)
            item[name] = None if stamp is None else round((stamp - request).total_seconds() * 1000)
        result.append(item)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="mujoong-live-realtime-1")
    parser.add_argument("--since", default="30m", help="Docker duration such as 30m or 2h")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}", args.container):
        parser.error("Invalid container name")
    if not re.fullmatch(r"[1-9][0-9]{0,3}[smh]", args.since):
        parser.error("Invalid duration")
    run = subprocess.run(["docker", "logs", "--since", args.since, args.container],
                         capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    if run.returncode:
        parser.exit(1, "Docker logs unavailable\n")
    result = summarize((run.stdout + "\n" + run.stderr).splitlines())
    print(json.dumps({"outputs": result, "note": "First PLAYING is a device playback mark, not an acoustic measurement."},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
