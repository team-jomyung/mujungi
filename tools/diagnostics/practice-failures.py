"""Read local practice failure categories only. Never print raw Docker logs or credentials."""
import argparse
import json
import re
import subprocess

PROVIDER = {
    "ProviderRejectedValue", "ProviderRejectedRequest", "ProviderRejectedEvent", "ProviderMissingParameter",
    "ProviderRejectedParameter", "ProviderRateLimited", "ProviderQuotaExhausted", "ProviderInternalFailure",
    "ProviderSessionUnavailable", "ProviderInputTooLong", "ProviderAudioFixtureActive", "ProviderRejectedDelegation",
    "ProviderUnclassifiedError", "ProviderDisconnected", "OutputAckMissing", "OutputNewSpeechNotObserved",
}
RULES = {
    "EMPTY_SCRIPT", "BASE_LAYOUT", "SCENE_KEY", "FIELD_FORMAT", "SPOKEN_FORMAT", "REFLECTION_NOT_ALLOWED",
    "PRIVATE_OR_FORBIDDEN_CONTENT", "TARGET_EXPRESSION_DISCLOSED", "PRESSURE_OR_ANSWER", "GRAPH_BASE_MISMATCH",
    "RELATION_MISMATCH", "CONDITION_MISSING", "PARTNER_LINE_MISSING", "ALREADY_SEEN", "UNCHANGED_SOURCE", "VARIANT_STRUCTURE",
}
REVIEWS = {
    "REJECT", "UNCERTAIN", "ANSWER_INCLUDED", "EARLY_CUE", "PRESSURE", "AGE_MISMATCH", "GOAL_MISMATCH", "REPETITION",
    "FORBIDDEN_CONTENT", "INCOMPLETE_SCENE", "INVARIANT_MISMATCH", "SKILL_MISMATCH", "TRANSFER_MISMATCH", "UNCHANGED_VARIANT",
}
ALLOWED = PROVIDER | {"ScenarioRule_" + x for x in RULES} | {"ScenarioReview_" + x for x in REVIEWS}
ALLOWED |= {"ScenarioValidation_INVALID_BASE", "ScenarioValidation_INVALID_VARIANT"}
ALLOWED |= {"ScenarioProvider_" + x for x in ("CONTENT", "TRANSIENT", "PERMANENT")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="mujoong-google-local")
    parser.add_argument("--since", default="15m", help="Recent duration such as 15m or 2h")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", args.project) or not re.fullmatch(r"[1-9][0-9]{0,3}[smh]", args.since):
        parser.error("Invalid project or duration")
    found = 0
    for service in ("core-api", "realtime"):
        try:
            run = subprocess.run(["docker", "logs", "--since", args.since, args.project + "-" + service + "-1"],
                                 capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
        except (OSError, subprocess.TimeoutExpired):
            print(json.dumps({"service": service, "read": "unavailable"}))
            continue
        if run.returncode:
            print(json.dumps({"service": service, "read": "unavailable"}))
            continue
        for line in (run.stdout + "\n" + run.stderr).splitlines():
            try:
                item = json.loads(line)
            except ValueError:
                continue
            if not isinstance(item, dict) or item.get("event") != "APP_ERROR":
                continue
            attrs = item.get("attrs")
            if not isinstance(attrs, dict) or not isinstance(attrs.get("errorKind"), str) or attrs["errorKind"] not in ALLOWED:
                continue
            stamp = item.get("ts", "")
            if not isinstance(stamp, str) or not re.fullmatch(r"[0-9TZ:.+\-]{1,40}", stamp):
                stamp = "unavailable"
            print(json.dumps({"service": service, "utc": stamp, "category": attrs["errorKind"]}))
            found += 1
    if not found:
        print(json.dumps({"result": "no_matching_events", "note": "This does not establish that practice succeeded."}))


if __name__ == "__main__":
    main()
