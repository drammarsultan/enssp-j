#!/usr/bin/env python3
"""
Summarises experiments/results/runs.csv (one or more files) into per-subject medians and
Vargha-Delaney A12 effect sizes of enSSP against every other configuration.

Usage: python3 experiments/summarize.py runs.csv [more_runs.csv ...] --out experiments/results
Writes summary.csv, summary.md and (if matplotlib is installed) mutation_scores.png.
"""
import argparse, csv, os, statistics
from collections import defaultdict

ORDER = ["enssp", "enssp-noreduce", "random", "ssp", "evosuite"]
LABEL = {"enssp": "enSSP", "enssp-noreduce": "enSSP (no reduction)", "random": "Random",
         "ssp": "SSP (no GA)", "evosuite": "EvoSuite"}


def a12(x, y):
    """P(X > Y) + 0.5 P(X = Y)."""
    gt = eq = 0
    for a in x:
        for b in y:
            gt += a > b
            eq += a == b
    n = len(x) * len(y)
    return (gt + 0.5 * eq) / n if n else float("nan")


def pct(c, t):
    return 100.0 * float(c) / float(t) if float(t) else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--out", default="experiments/results")
    a = ap.parse_args()
    rows = []
    for f in a.runs:
        with open(f) as fh:
            rows += list(csv.DictReader(fh))
    by = defaultdict(list)
    for r in rows:
        by[(r["subject"], r["config"])].append(r)
    subjects = []
    for r in rows:
        if r["subject"] not in subjects:
            subjects.append(r["subject"])

    def vals(s, c, key):
        out = []
        for r in by.get((s, c), []):
            if key == "line":
                out.append(pct(r["lineCovered"], r["lineTotal"]))
            elif key == "branch":
                out.append(pct(r["branchCovered"], r["branchTotal"]))
            elif r.get(key, "") != "":
                out.append(float(r[key]))
        return out

    os.makedirs(a.out, exist_ok=True)
    summary = []
    for s in subjects:
        for c in ORDER:
            if (s, c) not in by:
                continue
            rec = {"subject": s.split(".")[-1], "config": c, "runs": len(by[(s, c)])}
            for key in ["tests", "events", "genSeconds", "line", "branch", "mutationScore", "mutants"]:
                v = vals(s, c, key)
                rec[key] = round(statistics.median(v), 2) if v else ""
            if c != "enssp" and (s, "enssp") in by:
                rec["A12_MS_enssp_vs_this"] = round(a12(vals(s, "enssp", "mutationScore"), vals(s, c, "mutationScore")), 2)
                rec["A12_tests_enssp_vs_this"] = round(a12(vals(s, "enssp", "tests"), vals(s, c, "tests")), 2)
            summary.append(rec)
    keys = ["subject", "config", "runs", "tests", "events", "genSeconds", "line", "branch",
            "mutants", "mutationScore", "A12_MS_enssp_vs_this", "A12_tests_enssp_vs_this"]
    with open(os.path.join(a.out, "summary.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=keys)
        w.writeheader()
        for rec in summary:
            w.writerow({k: rec.get(k, "") for k in keys})
    with open(os.path.join(a.out, "summary.md"), "w") as fh:
        fh.write("Medians over seeds. A12 > 0.5 means enSSP tends to be higher.\n\n")
        fh.write("| Subject | Configuration | Runs | Tests | Events | Gen. time (s) | Line % | Branch % | Mutants | Mutation score % | A12 (MS) |\n")
        fh.write("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for rec in summary:
            fh.write("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" % (
                rec["subject"], LABEL[rec["config"]], rec["runs"], rec["tests"], rec["events"], rec["genSeconds"],
                rec["line"], rec["branch"], rec["mutants"], rec["mutationScore"], rec.get("A12_MS_enssp_vs_this", "")))
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        short = {"enssp": "E", "enssp-noreduce": "E-nr", "random": "R", "ssp": "S", "evosuite": "Evo"}
        colors = {"enssp": "#1f5f99", "enssp-noreduce": "#6a9fd0", "random": "#8c8c8c", "ssp": "#c8a24a", "evosuite": "#b04a4a"}
        ncol = 4
        nrow = (len(subjects) + ncol - 1) // ncol
        fig, axes = plt.subplots(nrow, ncol, figsize=(7.0, 2.4 * nrow), squeeze=False)
        for i, s in enumerate(subjects):
            ax = axes[i // ncol][i % ncol]
            data, labels, cols = [], [], []
            for c in ORDER:
                v = vals(s, c, "mutationScore")
                if v:
                    data.append(v); labels.append(short[c]); cols.append(colors[c])
            bp = ax.boxplot(data, widths=0.55, patch_artist=True, medianprops={"color": "black", "lw": 1})
            for patch, col in zip(bp["boxes"], cols):
                patch.set_facecolor(col); patch.set_alpha(0.75)
            ax.set_xticks(range(1, len(labels) + 1))
            ax.set_xticklabels(labels, fontsize=7)
            ax.tick_params(axis="y", labelsize=7)
            ax.set_title(s.split(".")[-1], fontsize=8)
            ax.grid(axis="y", alpha=0.3)
            if i % ncol == 0:
                ax.set_ylabel("Mutation score (%)", fontsize=7)
        for j in range(len(subjects), nrow * ncol):
            axes[j // ncol][j % ncol].axis("off")
        last = axes[-1][-1]
        last.text(0.02, 0.5, "E = enSSP-J\nE-nr = enSSP-J, no reduction\nR = random search\nS = plain SSP\nEvo = EvoSuite",
                  fontsize=7, va="center", transform=last.transAxes)
        fig.tight_layout()
        fig.savefig(os.path.join(a.out, "mutation_scores.png"), dpi=300)
    except ImportError:
        pass
    print(open(os.path.join(a.out, "summary.md")).read())


if __name__ == "__main__":
    main()
