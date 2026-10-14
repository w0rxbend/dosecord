#!/usr/bin/env python3
"""Focused positive and corruption tests for the repository-only UX guard."""
import copy
import importlib.util
import io
import pathlib
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

PATH = pathlib.Path(__file__).with_name("check-bot-ux-plan.py")
SPEC = importlib.util.spec_from_file_location("ux_guard", PATH)
assert SPEC is not None and SPEC.loader is not None
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


def node(g, nid):
    return next(n for n in g["nodes"] if n["id"] == nid)


def packet(g, pid):
    return next(p for p in g["packets"] if p["id"] == pid)


def drop_edges(g, predicate):
    g["edges"] = [e for e in g["edges"] if not predicate(e)]


# Each corruption must produce the named diagnostic, not an unrelated failure.
CASES = [
    ("duplicate_node", lambda g: g["nodes"].append(copy.deepcopy(g["nodes"][0])), "duplicate node IDs"),
    ("duplicate_packet", lambda g: g["packets"].append(copy.deepcopy(g["packets"][0])), "duplicate packet IDs"),
    ("unknown_packet", lambda g: packet(g, "UX-18").update(id="UX-99"), "packet IDs/mapping"),
    ("packet_cycle", lambda g: packet(g, "UX-00")["depends_on"].append("UX-15"), "packet dependency cycle"),
    ("unknown_dependency", lambda g: packet(g, "UX-01")["depends_on"].append("UX-99"), "unknown dependency"),
    ("mapping_drift", lambda g: packet(g, "UX-02").update(roadmap_slices=["M2.2"]), "mapping/dependencies disagree"),
    ("unknown_mapping", lambda g: packet(g, "UX-02").update(roadmap_slices=["M9.99"]), "unknown/empty roadmap mapping"),
    ("empty_mapping", lambda g: packet(g, "UX-02").update(roadmap_slices=[]), "unknown/empty roadmap mapping"),
    ("media_collapse", lambda g: packet(g, "UX-15").update(roadmap_slices=["M3.4"]), "unsafe media allocation"),
    ("missing_qualification", lambda g: packet(g, "UX-15").update(depends_on=["UX-10"]), "missing qualification prerequisite"),
    ("gate_acceptance", lambda g: g.update(gate_acceptance={}), "missing gate acceptance"),
    ("unknown_gate", lambda g: node(g, "root").update(gate="M9.99"), "unknown gate"),
    ("unknown_edge_gate", lambda g: g["edges"][0].update(required_gate="UX-99"), "unknown required gate"),
    ("unsafe_privacy", lambda g: node(g, "root").update(privacy="guild-public"), "unsafe privacy"),
    ("missing_route_gate", lambda g: node(g, "root").update(implemented_route_required=False), "route qualification missing"),
    ("unsafe_invariant", lambda g: g["invariants"].update(unknown_not_missed=False), "unsafe invariant"),
    ("parallel_status", lambda g: packet(g, "UX-01").update(status="green"), "cannot own completion status"),
    ("bad_parent", lambda g: node(g, "today").update(parent="missing"), "bad parent"),
    ("parent_cycle", lambda g: node(g, "today").update(parent="today"), "parent cycle"),
    ("bad_edge", lambda g: g["edges"][0].update(target="missing"), "bad edge"),
    ("empty_guard", lambda g: g["edges"][0].update(guard=" "), "unguarded edge"),
    ("invalid_effect", lambda g: g["edges"][0].update(effect="whatever"), "bad effect"),
    ("entrypoints", lambda g: g.update(entrypoints=["missing"]), "invalid entrypoints"),
    ("unreachable", lambda g: drop_edges(g, lambda e: e["target"] == "stats"), "unreachable nodes"),
    ("back_escape", lambda g: drop_edges(g, lambda e: e["source"] == "today" and e["action"] == "Back"), "no Back: today"),
    ("baseline_history", lambda g: node(g, "history").update(gate="M3.2"), "baseline history gated too late"),
    ("premature_image", lambda g: next(e for e in g["edges"] if e["action"] == "Infographic").update(required_gate="UX-13"), "premature image action"),
    ("image_pending_gate", lambda g: node(g, "stats.image_pending").update(gate="UX-13"), "image preparation visible too early"),
    ("late_log", lambda g: [e.update(guard="own account") for e in g["edges"] if e["source"] == "reminder" and e["action"] == "Taken"], "unbounded Taken: reminder"),
    ("old_dose", lambda g: drop_edges(g, lambda e: e["source"] == "dose.detail" and "correction required" in e["action"]), "no old-dose correction: dose.detail"),
    ("draft_suspend", lambda g: drop_edges(g, lambda e: e["source"] == "add.form" and e["target"] == "draft.suspended"), "no suspend: add.form"),
    ("stale_recovery", lambda g: drop_edges(g, lambda e: e["source"] == "add.form" and e["target"] == "recovery.stale"), "no current-step recovery: add.form"),
    ("stale_restore", lambda g: drop_edges(g, lambda e: e["source"] == "recovery.stale" and e["target"] == "add.form"), "no restored prompt: add.form"),
    ("uncertain_commit", lambda g: drop_edges(g, lambda e: e["action"] == "Replay reconciled committed outcome"), "no reconciled outcome"),
    ("policy_save", lambda g: drop_edges(g, lambda e: e["source"] == "medication.policy_review" and e["action"] == "Save reminder settings"), "no scoped policy save"),
    ("policy_route", lambda g: drop_edges(g, lambda e: e["source"] == "medication.settings" and e["target"] == "medication.policy"), "policy editor unreachable"),
]


class ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = guard.load()

    def test_baseline(self):
        result = guard.validate(*copy.deepcopy(self.original))
        self.assertEqual([], result["errors"])
        self.assertEqual(result["nodes"], result["reachable_nodes"])
        self.assertEqual(19, result["packets"])

    def test_roadmap_dependency_gap(self):
        g, plan, roadmap = copy.deepcopy(self.original)
        roadmap = roadmap.replace("| M2.2b | Executable runtime integration | S | M2.2a |", "| M2.2b | Executable runtime integration | S | M2.1 |")
        self.assertIn("roadmap dependency gap UX-02/M2.2b", "\n".join(guard.validate(g, plan, roadmap)["errors"]))

    def test_roadmap_cycle(self):
        g, plan, roadmap = copy.deepcopy(self.original)
        roadmap = roadmap.replace("| M2.2b | Executable runtime integration | S | M2.2a |", "| M2.2b | Executable runtime integration | S | M2.3 |")
        self.assertIn("roadmap dependency cycle", "\n".join(guard.validate(g, plan, roadmap)["errors"]))

    def test_missing_mapping(self):
        g, plan, roadmap = copy.deepcopy(self.original)
        roadmap = "\n".join(l for l in roadmap.splitlines() if not l.startswith("| UX-02 | M2.2b |"))
        self.assertIn("packet IDs/mapping", "\n".join(guard.validate(g, plan, roadmap)["errors"]))

    def test_missing_plan_packet(self):
        g, plan, roadmap = copy.deepcopy(self.original)
        plan = "\n".join(l for l in plan.splitlines() if not l.startswith("| UX-18 |"))
        self.assertIn("packet missing from plan UX-18", guard.validate(g, plan, roadmap)["errors"])

    def test_broken_plan_link(self):
        g, plan, roadmap = copy.deepcopy(self.original)
        self.assertIn("broken local link: missing-ux-contract.md", guard.validate(g, plan + "\n[bad](missing-ux-contract.md)", roadmap)["errors"])

    def test_malformed_cli_fails_closed(self):
        with patch.object(guard, "load", return_value=({}, "", "")), patch.object(guard.sys, "argv", [str(PATH)]), redirect_stderr(io.StringIO()):
            self.assertEqual(1, guard.main())

    def test_cli_rejects_corrupt_contract(self):
        g, plan, roadmap = copy.deepcopy(self.original)
        node(g, "root")["privacy"] = "guild-public"
        with patch.object(guard, "load", return_value=(g, plan, roadmap)), patch.object(guard.sys, "argv", [str(PATH)]), redirect_stdout(io.StringIO()):
            self.assertEqual(1, guard.main())


def mutation_test(mutate, diagnostic):
    def test(self):
        g, plan, roadmap = copy.deepcopy(self.original)
        mutate(g)
        errors = guard.validate(g, plan, roadmap)["errors"]
        self.assertTrue(any(diagnostic in e for e in errors), errors)
    return test


for name, mutate, diagnostic in CASES:
    setattr(ContractTests, f"test_reject_{name}", mutation_test(mutate, diagnostic))

if __name__ == "__main__":
    unittest.main(verbosity=2)
