#!/usr/bin/env python3
"""Validate the target UX contract against authoritative ROADMAP allocation.

Repository tooling only, stdlib only; not a product runtime. Structural guards
cannot establish implemented routes, rendering go, live smoke or in-use status.
Ported from the planning scratch validator; callable validation supports mutation
regression tests without writing corrupted documents into the checkout.
"""
import collections
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SLICE = r"(?:M\d+\.[\w.-]+|UX-\d{2})"
PACKET_IDS = {f"UX-{i:02d}" for i in range(19)}


def acyclic(deps, label, errors):
    remaining, order = set(deps), []
    while remaining:
        ready = sorted(i for i in remaining if set(deps[i]).issubset(order))
        if not ready:
            errors.append(f"{label} dependency cycle or unknown dependency")
            break
        order.extend(ready)
        remaining.difference_update(ready)
    return order


def ancestors(deps, start):
    seen, pending = set(), list(deps.get(start, []))
    while pending:
        item = pending.pop()
        if item not in seen:
            seen.add(item)
            pending.extend(deps.get(item, []))
    return seen


def tables(roadmap, errors):
    status_section = roadmap.split("## 11. Appendix B")[1].split("## 12.")[0]
    slices, deps = {}, {}
    for line in status_section.splitlines():
        cells = [x.strip() for x in line.strip().strip("|").split("|")]
        if not line.startswith("| ") or not re.fullmatch(SLICE, cells[0]):
            continue
        if len(cells) != 6:
            errors.append(f"invalid roadmap row: {cells[0]}")
            continue
        sid, _, _, dependency, _, status = cells
        if sid in slices:
            errors.append(f"duplicate roadmap slice: {sid}")
        slices[sid] = status
        if status not in {"todo", "building", "in-progress", "green", "in-use", "blocked", "deferred"}:
            errors.append(f"invalid roadmap status: {sid}")
        deps[sid] = re.findall(SLICE, dependency)
        if dependency == "all of M7":
            deps[sid] = [f"M7.{i}" for i in range(1, 9)]
    if not slices:
        errors.append("missing roadmap slice table")
    mapping = {}
    for line in roadmap.splitlines():
        cells = [x.strip() for x in line.strip().strip("|").split("|")]
        if line.startswith("| UX-") and len(cells) == 4:
            if cells[0] in mapping:
                errors.append(f"duplicate packet mapping: {cells[0]}")
            mapping[cells[0]] = (re.findall(SLICE, cells[1]),
                                 re.findall(r"UX-\d{2}", cells[2]))
            if not cells[3]:
                errors.append(f"missing mapped acceptance: {cells[0]}")
    return slices, deps, mapping


def validate(g, plan, roadmap, root=ROOT):
    errors = []
    slices, roadmap_deps, mapping = tables(roadmap, errors)
    acyclic(roadmap_deps, "roadmap", errors)
    node_ids = [n["id"] for n in g["nodes"]]
    nodes = {n["id"]: n for n in g["nodes"]}
    packet_ids = [p["id"] for p in g["packets"]]
    packets = {p["id"]: p for p in g["packets"]}
    if len(node_ids) != len(nodes):
        errors.append("duplicate node IDs")
    if len(packet_ids) != len(packets):
        errors.append("duplicate packet IDs")
    if set(packets) != PACKET_IDS or set(mapping) != PACKET_IDS:
        errors.append("packet IDs/mapping must be UX-00 through UX-18")
    if g["schema_version"] != 1:
        errors.append("unsupported graph schema")

    def no_status(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in {"status", "completion_status", "completed"}:
                    errors.append("graph cannot own completion status")
                no_status(child)
        elif isinstance(value, list):
            for child in value:
                no_status(child)
    no_status(g)
    expected_invariants = {
        "no_clinical_advice": True, "dm_only": True,
        "no_sensitive_logs_at_any_level": True, "unknown_not_missed": True,
        "manual_log_single_use_intent": True, "one_snapshot_for_text_alt_png": True,
        "gate_visibility_requires_route": True, "max_discord_rows": 5,
        "max_buttons_per_row": 5, "max_text_chunk_chars": 2000,
        "routine_prompt_fixed_copy_chars": 160, "routine_outcome_chars": 80,
        "ack_target_ms": 1500, "draft_idle_minutes": 30, "draft_grace_minutes": 30,
    }
    for key, value in expected_invariants.items():
        if type(g["invariants"].get(key)) is not type(value) or g["invariants"].get(key) != value:
            errors.append(f"unsafe invariant: {key}")
    gate_ids = set(slices) | set(packets)
    for n in nodes.values():
        if n["parent"] is not None and n["parent"] not in nodes:
            errors.append(f"bad parent {n['id']}")
        if n["gate"] not in gate_ids:
            errors.append(f"unknown gate {n['id']}: {n['gate']}")
        if n["privacy"] != "bot-dm-only":
            errors.append(f"unsafe privacy {n['id']}")
        if n.get("implemented_route_required") is not True:
            errors.append(f"route qualification missing: {n['id']}")
        seen, parent = {n["id"]}, n["parent"]
        while parent in nodes:
            if parent in seen:
                errors.append(f"parent cycle: {n['id']}")
                break
            seen.add(parent)
            parent = nodes[parent]["parent"]
    for e in g["edges"]:
        if e["source"] not in nodes or e["target"] not in nodes:
            errors.append(f"bad edge {e}")
        if not e.get("guard", "").strip():
            errors.append(f"unguarded edge {e}")
        if e["effect"] not in {"read", "draft", "commit", "system"}:
            errors.append(f"bad effect {e}")
        if "required_gate" in e and e["required_gate"] not in gate_ids:
            errors.append(f"unknown required gate: {e['required_gate']}")
        if e["action"] in {"Back", "Menu"} and e["effect"] == "commit":
            errors.append("navigation cannot commit")
    packet_deps = {p["id"]: p["depends_on"] for p in packets.values()}
    order = acyclic(packet_deps, "packet", errors)
    assigned = []
    for pid, p in packets.items():
        if not re.search(r"\| " + re.escape(pid) + r" \|", plan):
            errors.append(f"packet missing from plan {pid}")
        official = p["roadmap_slices"]
        assigned.extend(official)
        if not official or any(i not in slices for i in official):
            errors.append(f"unknown/empty roadmap mapping: {pid}")
        if mapping.get(pid) != (official, p["depends_on"]):
            errors.append(f"roadmap mapping/dependencies disagree: {pid}")
        for predecessor in p["depends_on"]:
            if predecessor not in packets:
                errors.append(f"unknown dependency {pid}: {predecessor}")
                continue
            # UX-00 is a merge/readback governance precondition, not a new
            # product dependency on the already-unblocked M2.2 baseline.
            if predecessor == "UX-00":
                continue
            for sid in official:
                missing = set(packets[predecessor]["roadmap_slices"]) - ancestors(roadmap_deps, sid)
                if missing:
                    errors.append(f"roadmap dependency gap {pid}/{sid}: {sorted(missing)}")
    if len(assigned) != len(set(assigned)):
        errors.append("official slice assigned to multiple packets")
    # Even synchronized edits cannot silently collapse deferred media into text
    # or bypass the independent transport/visual visibility gates.
    for pid, expected in {"UX-11": ["M3.4"], "UX-12": ["M3.4a"],
                          "UX-13": ["M3.4b"], "UX-14": ["M3.4c"],
                          "UX-15": ["M3.4d"]}.items():
        if packets.get(pid, {}).get("roadmap_slices") != expected:
            errors.append(f"unsafe media allocation: {pid}")
    for pid, required in {"UX-07": {"UX-02", "UX-03", "UX-04", "UX-05", "UX-06"},
                          "UX-13": {"UX-12", "UX-08"},
                          "UX-14": {"UX-13", "UX-07"},
                          "UX-15": {"UX-14", "UX-10"}}.items():
        if not required.issubset(ancestors(packet_deps, pid)):
            errors.append(f"missing qualification prerequisite: {pid}")
    for gate in ("UX-12", "UX-15"):
        if not g.get("gate_acceptance", {}).get(gate):
            errors.append(f"missing gate acceptance: {gate}")

    adj = collections.defaultdict(list)
    for e in g["edges"]:
        adj[e["source"]].append(e["target"])
    if not g["entrypoints"] or not set(g["entrypoints"]).issubset(nodes):
        errors.append("invalid entrypoints")
    visited = set(g["entrypoints"])
    queue = collections.deque(visited)
    while queue:
        for target in adj[queue.popleft()]:
            if target not in visited:
                visited.add(target)
                queue.append(target)
    if set(nodes) - visited:
        errors.append(f"unreachable nodes: {sorted(set(nodes) - visited)}")
    for n in nodes.values():
        out = [e for e in g["edges"] if e["source"] == n["id"]]
        if n["parent"] and not any(e["action"] == "Back" and e["effect"] != "commit" for e in out):
            errors.append(f"no Back: {n['id']}")
        if n["kind"] == "wizard":
            for target, label in [("draft.suspended", "suspend"), ("recovery.stale", "current-step recovery")]:
                if not any(e["target"] == target for e in out):
                    errors.append(f"no {label}: {n['id']}")
            if not any(e["action"] == "Cancel" for e in out):
                errors.append(f"no Cancel: {n['id']}")
            if not any(e["source"] == "recovery.stale" and e["target"] == n["id"] for e in g["edges"]):
                errors.append(f"no restored prompt: {n['id']}")
    for link in re.findall(r"\]\(([^)]+)\)", plan):
        if "://" not in link and not link.startswith("#") and not (root / "docs" / link.split("#")[0]).exists():
            errors.append(f"broken local link: {link}")
    if nodes.get("history", {}).get("gate") != "M1.9":
        errors.append("baseline history gated too late")
    images = [e for e in g["edges"] if e["source"] == "stats" and e["action"] == "Infographic"]
    if not images or any(e.get("required_gate") != "UX-15" for e in images):
        errors.append("premature image action")
    if nodes.get("stats.image_pending", {}).get("gate") != "UX-15":
        errors.append("image preparation visible too early")
    for source in ["reminder", "dose.detail"]:
        direct = [e for e in g["edges"] if e["source"] == source and e["action"] == "Taken" and e["effect"] == "commit"]
        if not direct or any("lateLogWindowMinutes" not in e["guard"] for e in direct):
            errors.append(f"unbounded Taken: {source}")
        if not any(e["source"] == source and e["target"] == "dose.correct" and "correction required" in e["action"] and e["effect"] == "read" for e in g["edges"]):
            errors.append(f"no old-dose correction: {source}")
    for source, action, effect, label in [
        ("recovery.uncertain", "Replay reconciled committed outcome", "read", "reconciled outcome"),
        ("medication.policy_review", "Save reminder settings", "commit", "scoped policy save")]:
        if not any(e["source"] == source and e["action"] == action and e["effect"] == effect for e in g["edges"]):
            errors.append(f"no {label}")
    if not any(e["source"] == "medication.settings" and e["target"] == "medication.policy" for e in g["edges"]):
        errors.append("policy editor unreachable")
    return {"nodes": len(nodes), "edges": len(g["edges"]), "packets": len(packets),
            "reachable_nodes": len(visited), "packet_order": order, "errors": errors}


def load(root=ROOT):
    return (json.loads((root / "docs/design-tracks/bot-ux-graph.json").read_text()),
            (root / "docs/BOT_UX_PLAN.md").read_text(),
            (root / "docs/ROADMAP.md").read_text())


def main():
    root = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    try:
        result = validate(*load(root), root=root)
    except (KeyError, ValueError, TypeError, IndexError, AttributeError, OSError) as exc:
        print(f"FAIL: malformed or missing UX contract: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    if result["errors"]:
        return 1
    print("PASS: roadmap allocation/DAG, graph gates/privacy/recovery and local links (not runtime qualification)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
