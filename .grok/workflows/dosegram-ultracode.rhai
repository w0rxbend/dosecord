let meta = #{
    name: "dosegram-ultracode",
    description: "Build four open Dosegram handy gaps in parallel, verify each, merge only the accepted branches to main",
    when_to_use: "Local Dosegram main is clean enough to branch. Pass args.root. Does not push or deploy.",
    phases: [
        #{ title: "Build", detail: "four slices, one worktree each" },
        #{ title: "Verify", detail: "one skeptic per slice" },
        #{ title: "Merge", detail: "accepted branches onto main, no push" },
    ],
};

let build_schema = #{
    "type": "object",
    "required": ["branch", "committed", "summary"],
    "properties": #{
        "branch": #{ "type": "string" },
        "committed": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "tests": #{ "type": "string" },
    },
};

let verdict_schema = #{
    "type": "object",
    "required": ["accept", "reason", "evidence"],
    "properties": #{
        "accept": #{ "type": "boolean" },
        "reason": #{ "type": "string" },
        "evidence": #{ "type": "string" },
    },
};

fn house_rules() {
    let p = "";
    p += "Read AGENTS.md, docs/AGENTIC_DEVELOPMENT.md, and the slice text you are given before you edit. ";
    p += "Read these skills and apply them to every line you change: ";
    p += "/home/worxbend/.agents/skills/code-complete/SKILL.md, ";
    p += "/home/worxbend/.agents/skills/clean-code-guard/SKILL.md, ";
    p += "/home/worxbend/.agents/skills/code-perfection/SKILL.md, ";
    p += "/home/worxbend/.agents/skills/code-review-and-quality/SKILL.md, ";
    p += "/home/worxbend/.agents/skills/test-driven-development/SKILL.md, ";
    p += "/home/worxbend/.agents/skills/worxbend-repository-context/SKILL.md. ";
    p += "Scala files also follow the worxbend Scala notes inside code-perfection. ";
    p += "One slice only. Do not invent a product behaviour that the named document does not already ask for. ";
    p += "Copy stays non-clinical: record what the user did, do not advise a dose, interpret a value, or compute a health score. ";
    p += "Every new user-facing string is an English and Ukrainian pair. ";
    p += "Every new image has a text equivalent. ";
    p += "Do not edit a committed migration. Do not add a migration. The next free version is V23 and this run assigns it to nobody. ";
    p += "Do not edit build.mill, contracts/, core/ports/UnitOfWork.scala, infra Settings.scala, .env.example, ";
    p += "app Main.scala, app Adapters.scala, docker/Dockerfile, docker/compose.yml, .github/workflows/ci.yml, ";
    p += "api/openapi.json, or docs/ROADMAP.md. ";
    p += "Do not read or print .env or any secret file. Do not log medication names, notes, or message bodies. ";
    p += "Do not push, deploy, send a Telegram message, or talk to the live database. ";
    p += "Do not touch .claude/worktrees or the dirty onboarding worktree. ";
    p += "Write the test first. Commit on your named branch with a conventional message only after that test passes. ";
    p += "If the test cannot pass, commit nothing and set committed=false. ";
    p += "Do not mark any roadmap slice green. ";
    p
}

fn track_prompt(root, id) {
    let p = "";
    p += "You implement one Dosegram gap in your own git worktree. Repository root to branch from: " + root + ". ";
    p += "Create and commit only on the branch named below. Start from current main. Do not merge to main yourself. ";
    p += house_rules();
    if id == "week-cards" {
        p += "Branch: feat/ultra-week-cards. ";
        p += "Gap: VISUALS_AND_ANALYTICS section 6.21. IMG-20 (water, 7 days), IMG-21 (sleep, 7 nights) and IMG-22 (mood by weekday) are already drawn. ";
        p += "No chat button sends them. IMG-19 already sends from Show as image on the seven-day /stats. ";
        p += "Add three buttons on that seven-day view that send IMG-20, IMG-21 and IMG-22, each with the existing text equivalent, discreet spoiler when the card is personal, and the account locale. ";
        p += "Follow the existing Show as image path in app StatusImages and CaptureLaunchSuite. ";
        p += "You may edit the chat action path and the copy keys those buttons need. You may not edit miniapp/ or report-renderer/. ";
        p += "Another track owns report-renderer. Do not restyle the cards. ";
    } else if id == "today-sheets" {
        p += "Branch: feat/ultra-today-sheets. ";
        p += "Gap: MINI_APP Today. A dose row opens an occurrence sheet: scheduled time, current state, action history, and the corrections the API already accepts. ";
        p += "Read MINI_APP the Today section and MA-SCHED-5. ";
        p += "If POST /occurrences/{id}/actions already accepts taken, skipped, snoozed and undo, the sheet calls those. ";
        p += "If the API has no Taken-at or note field, do not invent a route and say so in summary. ";
        p += "As-needed medications with no occurrences appear in an as-needed sheet that states the user's own limit facts and shows no reminder times. ";
        p += "Add one inline mood check-in on Today that uses the existing mood route, one check-in, no interpretation of the number. ";
        p += "Edit only miniapp Today screens, the dose-row component they already use, their tests, and new i18n keys whose names start with todaySheet, asNeeded or moodInline. ";
        p += "Do not edit miniapp insights screens. Another track owns those. ";
    } else if id == "insights-handy" {
        p += "Branch: feat/ultra-insights-handy. ";
        p += "Gap: MINI_APP MA-INS-8 and the eighth-round chart notes. ";
        p += "Blocks below the first two load when they are scrolled within 200 px of the viewport. The first two blocks stay on the first insights/overview call. ";
        p += "The mood chart axis label stays readable at a 360 px viewport. ";
        p += "Tapping a week chart point opens the existing day detail for that date. markDates already exists. Do not add a new route. ";
        p += "Do not change insight rules, do not add a score, do not explain a cause. ";
        p += "Edit only miniapp insights screens, their chart components, their tests, and new i18n keys whose names start with insightsLazy or insightsTap. ";
        p += "Do not edit miniapp Today screens. ";
    } else if id == "refill-img04" {
        p += "Branch: feat/ultra-refill-img04. ";
        p += "Gap: the refill card IMG-15 is drawn but missing from the golden suite. Add the golden the way the other cards are checked. ";
        p += "Gap: a medication-only IMG-04 weekly report. Read VISUALS_AND_ANALYTICS section 6.5 and VA-IMG-04a. ";
        p += "A week whose only records are medication still draws the weekly report, lists the other tracked domains as No records this week, and omits a domain that is not tracked. Text equivalent included. No health score. ";
        p += "Edit only files under report-renderer/. Do not edit app/StatusImages.scala. Another track owns chat buttons. ";
    }
    p += "Run the tests that cover your change. Record the command and the pass count in tests. ";
    p += "Return branch, committed, summary, and tests. ";
    p
}

fn verify_prompt(root, id, branch) {
    let p = "";
    p += "Adversarially verify one Dosegram branch. Default accept=false. Do not edit, commit, merge, or push. ";
    p += "Repository: " + root + ". Branch: " + branch + ". Slice id: " + id + ". ";
    p += "Use git diff and git show against main. Open the files the diff touches. ";
    p += "accept=true only when all of these hold: the branch exists; the diff matches the slice and no other product; ";
    p += "a test was added or updated for the new behaviour; no migration, no OpenAPI, no Dockerfile, no ROADMAP status edit, no .env; ";
    p += "copy is non-clinical; new strings have English and Ukrainian; you quote a path you opened in evidence. ";
    p += "A branch that does not exist is accept=false. A green claim without a test name is accept=false. ";
    p += "Slice reminder: " + track_prompt(root, id);
    p
}

let root = if args == () { () } else { args.root };
if root == () {
    pause("verification", "Pass args.root — the Dosegram repository path.");
}

let tracks = ["week-cards", "today-sheets", "insights-handy", "refill-img04"];
let branches = #{
    "week-cards": "feat/ultra-week-cards",
    "today-sheets": "feat/ultra-today-sheets",
    "insights-handy": "feat/ultra-insights-handy",
    "refill-img04": "feat/ultra-refill-img04",
};

phase("Build");
let jobs = [];
for id in tracks {
    jobs.push(#{
        prompt: track_prompt(root, id),
        label: "build:" + id,
        capability_mode: "all",
        isolation_worktree: true,
        output_schema: build_schema,
    });
}
let built = parallel(jobs);

phase("Verify");
let vjobs = [];
for id in tracks {
    vjobs.push(#{
        prompt: verify_prompt(root, id, branches[id]),
        label: "verify:" + id,
        capability_mode: "execute",
        output_schema: verdict_schema,
    });
}
let verdicts = parallel(vjobs);

let accepted = [];
let rejected = [];
let i = 0;
for id in tracks {
    let v = verdicts[i];
    let keep = false;
    if v != () && v.success && type_of(v.output) == "map" {
        if v.output.accept == true {
            let evidence = "";
            if v.output.evidence != () {
                evidence = v.output.evidence;
            }
            if type_of(evidence) == "string" && evidence != "" {
                keep = true;
            }
        }
    }
    if keep {
        accepted.push(branches[id]);
    } else {
        rejected.push(id);
    }
    i += 1;
}

log(accepted.len().to_string() + " accepted, " + rejected.len().to_string() + " rejected");

if accepted.len() == 0 {
    complete(#{
        summary: "No branch passed verification. Nothing merged.",
        accepted: accepted,
        rejected: rejected,
    });
}

phase("Merge");
let merge_prompt = "";
merge_prompt += "Merge accepted Dosegram branches into main in the repository " + root + ". ";
merge_prompt += "Accepted branches, in this order: ";
for b in accepted {
    merge_prompt += b + " ";
}
merge_prompt += ". Isolation worktrees do not publish branches into the parent repo. ";
merge_prompt += "If a branch is missing, fetch it from the builder worktree before merging. ";
merge_prompt += "Builder agent ids and branches: ";
let j = 0;
for id in tracks {
    let b = built[j];
    if b != () && b.agent_id != () {
        merge_prompt += id + " agent " + b.agent_id + " branch " + branches[id] + ". ";
        merge_prompt += "Look under /home/worxbend/.grok/worktrees for a directory named subagent-" + b.agent_id + ". ";
    }
    j += 1;
}
merge_prompt += "Use git fetch <worktree> <branch>:<branch>. ";
merge_prompt += "Skip a branch that does not exist or is already contained in main. ";
merge_prompt += "Use git merge --no-ff. Message shape: feat: merge <branch> plus one line of why. ";
merge_prompt += "Do not push. Do not deploy. Do not read .env. Do not start the live bot. Do not drop or migrate the live database. ";
merge_prompt += "Do not stage or revert unrelated dirty files. Leave .grok/workflows/dosegram-ultracode.rhai alone if it is uncommitted. ";
merge_prompt += "Do not edit docs/ROADMAP.md and do not mark a slice green. ";
merge_prompt += "If two sides both added i18n keys, keep both keys. ";
merge_prompt += "After the merges, run ./mill __.compile. If compile fails, fix only the merge break, with a test if you change behaviour. ";
merge_prompt += "Then run the focused module tests for modules you merged: app, report-renderer, and pnpm --dir miniapp run test, whichever the diff touched. ";
merge_prompt += "pnpm is /home/worxbend/.local/share/pnpm/bin/pnpm and node is /home/worxbend/.nvm/versions/node/v27.0.0-alpha.0.0.0/bin. ";
merge_prompt += "Return merged (array of shas), failed (array of branch names), and tests (one string). ";

let merge_schema = #{
    "type": "object",
    "required": ["merged", "failed", "tests"],
    "properties": #{
        "merged": #{ "type": "array", "items": #{ "type": "string" } },
        "failed": #{ "type": "array", "items": #{ "type": "string" } },
        "tests": #{ "type": "string" },
    },
};

let merged = agent(merge_prompt, #{
    label: "merge:main",
    capability_mode: "all",
    output_schema: merge_schema,
});

let report = "";
report += "Accepted: " + accepted.len().to_string() + ". Rejected: " + rejected.len().to_string() + ". ";
if merged != () && merged.success {
    report += "Merge agent finished. ";
} else {
    report += "Merge agent failed. ";
}
let path = write_scratch_file("ultracode-report.md", report);
complete(#{
    summary: report,
    path: path,
    accepted: accepted,
    rejected: rejected,
});
