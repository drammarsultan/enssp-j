#!/usr/bin/env python3
"""
Illustrative evaluation of enSSP-J.

For every subject class, generator configuration and seed this script
  1. generates a JUnit 4 suite (enSSP-J modes, or EvoSuite),
  2. compiles it and runs it once with the JaCoCo agent (line and branch coverage),
  3. runs PIT mutation analysis with its default mutators on the class under test,
and appends one row per run to <out>/runs.csv. Nothing is estimated: every value in the
CSV is read from the tools' own reports.

Usage:
  python3 experiments/run_experiments.py --tools <dir> [--seeds 10] [--budget 30]
         [--configs enssp,enssp-noreduce,random,ssp,evosuite] [--out experiments/results]

<dir> must contain: pitest-*.jar, pitest-command-line-*.jar (+ its dependencies:
asm*, commons-text, commons-lang3, jopt-simple), junit-4.13.2.jar, hamcrest-core-1.3.jar,
lib/jacocoagent.jar, lib/jacococli.jar and, for the EvoSuite baseline, evosuite-1.2.0.jar.
Set JAVA8 to a Java 8 executable for EvoSuite (it does not run on newer JVMs).
"""
import argparse, csv, glob, json, os, shutil, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SUBJ = os.path.join(ROOT, "examples", "subjects")
SUBJECTS = [
    "sequences.BankAccount",
    "sequences.Stack",
    "sequences.BinTree",
    "sequences.BST",
    "rbt.TreeMap",
    "org.apache.commons.collections4.queue.CircularFifoQueue",
]
FIELDS = ["subject", "config", "seed", "budget", "bound", "tests", "events", "genSeconds",
          "targets", "targetsCovered", "partitions", "systemIds",
          "testsFailing", "lineCovered", "lineTotal", "branchCovered", "branchTotal",
          "mutants", "killed", "survived", "noCoverage", "timedOut", "mutationScore"]


def sh(cmd, cwd=None, timeout=None, env=None):
    p = subprocess.run(cmd, cwd=cwd, timeout=timeout, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return p.returncode, p.stdout, p.stderr


def jars(tools, pattern):
    return sorted(glob.glob(os.path.join(tools, pattern)))


def generate_enssp(cls, config, seed, budget, work, bound=None):
    mode = "enssp" if config.startswith("enssp") else config
    cmd = ["java", "-jar", os.path.join(ROOT, "target", "enssp-j-1.0.0.jar"), "--class", cls,
           "--cp", os.path.join(SUBJ, "classes"), "--out", os.path.join(work, "tests"),
           "--mode", mode, "--budget", str(budget), "--seed", str(seed)]
    if config == "enssp-noreduce":
        cmd.append("--no-reduce")
    if bound is not None:
        cmd += ["--bound", str(bound)]
    t0 = time.time()
    rc, out, err = sh(cmd, timeout=budget * 20 + 600)
    dt = time.time() - t0
    if rc != 0:
        raise RuntimeError("enSSP-J failed: " + err[-2000:])
    rep = json.loads(out)
    test_cls = cls + "_ESSP_Test"
    return test_cls, dt, rep


def generate_evosuite(cls, seed, budget, work, tools):
    java8 = os.environ.get("JAVA8", "java")
    evo = jars(tools, "evosuite-*.jar")[0]
    cmd = [java8, "-jar", evo, "-class", cls, "-projectCP", os.path.join(SUBJ, "classes"),
           "-seed", str(seed), "-Dsearch_budget=%d" % budget, "-Dstopping_condition=MaxTime",
           "-Dtest_dir=" + os.path.join(work, "tests"), "-Dreport_dir=" + os.path.join(work, "evo-report"),
           "-Dno_runtime_dependency=true", "-Dshow_progress=false", "-Dtest_scaffolding=false"]
    env = dict(os.environ)
    jhome = os.path.dirname(os.path.dirname(os.path.realpath(java8))) if os.path.isabs(java8) else None
    if jhome:  # EvoSuite spawns client JVMs with the 'java' found on PATH
        env["JAVA_HOME"] = jhome
        env["PATH"] = os.path.join(jhome, "bin") + os.pathsep + env.get("PATH", "")
    t0 = time.time()
    rc, out, err = sh(cmd, cwd=work, timeout=budget * 10 + 900, env=env)
    dt = time.time() - t0
    files = glob.glob(os.path.join(work, "tests", "**", "*_ESTest.java"), recursive=True)
    if not files:
        raise RuntimeError("EvoSuite produced no tests: " + (out + err)[-2000:])
    return cls + "_ESTest", dt, {}


def count_tests(src_dir):
    n = 0
    for f in glob.glob(os.path.join(src_dir, "**", "*.java"), recursive=True):
        with open(f, encoding="utf-8") as fh:
            n += fh.read().count("@Test")
    return n


def measure(cls, test_cls, work, tools, pit=True):
    junit = [os.path.join(tools, "junit-4.13.2.jar"), os.path.join(tools, "hamcrest-core-1.3.jar")]
    classes = os.path.join(SUBJ, "classes")
    tc = os.path.join(work, "tc")
    os.makedirs(tc, exist_ok=True)
    srcs = glob.glob(os.path.join(work, "tests", "**", "*.java"), recursive=True)
    extra = jars(tools, "evosuite-*.jar") if test_cls.endswith("_ESTest") else []
    cp = os.pathsep.join([classes] + junit + extra)
    rc, out, err = sh(["javac", "-nowarn", "-encoding", "UTF-8", "-cp", cp, "-d", tc] + srcs)
    if rc != 0:
        raise RuntimeError("test compilation failed: " + err[-3000:])
    runcp = os.pathsep.join([classes, tc] + junit + extra)

    # JUnit run under JaCoCo.
    execf = os.path.join(work, "jacoco.exec")
    agent = os.path.join(tools, "lib", "jacocoagent.jar")
    rc, out, err = sh(["java", "-javaagent:%s=destfile=%s,includes=%s:%s$*" % (agent, execf, cls, cls),
                       "-cp", runcp, "org.junit.runner.JUnitCore", test_cls], timeout=900)
    failing = 0
    for line in out.splitlines():
        if line.startswith("Tests run:") and "Failures:" in line:
            failing = int(line.split("Failures:")[1].strip())
    # The unit under test is the class and its nested classes (e.g. tree nodes, views, iterators).
    base = os.path.join(classes, cls.replace(".", os.sep))
    cls_files = [base + ".class"] + sorted(glob.glob(glob.escape(base) + "$*.class"))
    csvf = os.path.join(work, "jacoco.csv")
    cf_args = []
    for f in cls_files:
        cf_args += ["--classfiles", f]
    rc, out, err = sh(["java", "-jar", os.path.join(tools, "lib", "jacococli.jar"), "report", execf] + cf_args + ["--csv", csvf])
    lc = lt = bc = bt = 0
    with open(csvf) as fh:
        for r in csv.DictReader(fh):
            lc += int(r["LINE_COVERED"]); lt += int(r["LINE_COVERED"]) + int(r["LINE_MISSED"])
            bc += int(r["BRANCH_COVERED"]); bt += int(r["BRANCH_COVERED"]) + int(r["BRANCH_MISSED"])

    cov = {"testsFailing": failing, "lineCovered": lc, "lineTotal": lt, "branchCovered": bc, "branchTotal": bt}
    if not pit:
        cov.update({"mutants": "", "killed": "", "survived": "", "noCoverage": "", "timedOut": "", "mutationScore": ""})
        return cov

    # PIT with default mutators; failing tests are excluded from its green suite by skipFailingTests.
    pitcp = os.pathsep.join([j for j in jars(tools, "*.jar") if "evosuite" not in j and "jacoco" not in j])
    rep = os.path.join(work, "pit")
    rc, out, err = sh(["java", "-cp", os.pathsep.join([pitcp, classes, tc] + extra),
                       "org.pitest.mutationtest.commandline.MutationCoverageReport",
                       "--reportDir", rep, "--targetClasses", cls + "," + cls + "$*", "--targetTests", test_cls,
                       "--sourceDirs", os.path.join(SUBJ, "src"), "--outputFormats", "CSV",
                       "--timestampedReports=false", "--skipFailingTests=true", "--threads", "1"], timeout=3600)
    muts = os.path.join(rep, "mutations.csv")
    if not os.path.exists(muts):
        raise RuntimeError("PIT failed: " + (out + err)[-3000:])
    status = {}
    with open(muts) as fh:
        for row in csv.reader(fh):
            if len(row) >= 6:
                status[row[5]] = status.get(row[5], 0) + 1
    total = sum(status.values())
    detected = status.get("KILLED", 0) + status.get("TIMED_OUT", 0) + status.get("MEMORY_ERROR", 0)
    return {"testsFailing": failing, "lineCovered": lc, "lineTotal": lt, "branchCovered": bc,
            "branchTotal": bt, "mutants": total, "killed": status.get("KILLED", 0),
            "survived": status.get("SURVIVED", 0), "noCoverage": status.get("NO_COVERAGE", 0),
            "timedOut": status.get("TIMED_OUT", 0),
            "mutationScore": round(100.0 * detected / total, 2) if total else 0.0}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tools", required=True)
    ap.add_argument("--seeds", type=int, default=10)
    ap.add_argument("--budget", type=int, default=30)
    ap.add_argument("--configs", default="enssp,enssp-noreduce,random,ssp")
    ap.add_argument("--subjects", default=",".join(SUBJECTS))
    ap.add_argument("--out", default=os.path.join(ROOT, "experiments", "results"))
    ap.add_argument("--keep", action="store_true", help="keep generated suites and reports")
    ap.add_argument("--bound", type=int, default=None, help="enSSP-J abstraction bound (default: tool default)")
    ap.add_argument("--no-pit", action="store_true", help="coverage only")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    runs = os.path.join(a.out, "runs.csv")
    done = set()
    if os.path.exists(runs):
        with open(runs) as fh:
            for r in csv.DictReader(fh):
                done.add((r["subject"], r["config"], r["seed"]))
    new = not os.path.exists(runs)
    with open(runs, "a", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS)
        if new:
            w.writeheader()
        for cls in a.subjects.split(","):
            for config in a.configs.split(","):
                for seed in range(1, a.seeds + 1):
                    if (cls, config, str(seed)) in done:
                        continue
                    work = os.path.join(a.out, "work", cls, config, str(seed))
                    shutil.rmtree(work, ignore_errors=True)
                    os.makedirs(work)
                    try:
                        if config == "evosuite":
                            test_cls, dt, rep = generate_evosuite(cls, seed, a.budget, work, a.tools)
                        else:
                            test_cls, dt, rep = generate_enssp(cls, config, seed, a.budget, work, a.bound)
                        m = measure(cls, test_cls, work, a.tools, pit=not a.no_pit)
                    except Exception as e:  # recorded, never silently dropped
                        print("FAILED", cls, config, seed, str(e)[:400], file=sys.stderr, flush=True)
                        with open(os.path.join(a.out, "failures.log"), "a") as lf:
                            lf.write("%s %s %s %s\n" % (cls, config, seed, str(e).replace("\n", " ")[:2000]))
                        continue
                    row = {"subject": cls, "config": config, "seed": seed, "budget": a.budget,
                           "bound": a.bound if a.bound is not None and config != "evosuite" else "",
                           "tests": rep.get("tests", count_tests(os.path.join(work, "tests"))),
                           "events": rep.get("eventsInSuite", ""), "genSeconds": round(dt, 2),
                           "targets": rep.get("targets", ""), "targetsCovered": rep.get("targetsCovered", ""),
                           "partitions": rep.get("partitions", ""), "systemIds": rep.get("systemIds", "")}
                    row.update(m)
                    w.writerow(row)
                    fh.flush()
                    print(cls, config, seed, "bound=%s" % a.bound, "MS=%s%% line=%d/%d branch=%d/%d tests=%s" % (
                        m["mutationScore"], m["lineCovered"], m["lineTotal"], m["branchCovered"],
                        m["branchTotal"], row["tests"]), flush=True)
                    if not a.keep:
                        for d in ("tc", "pit"):
                            shutil.rmtree(os.path.join(work, d), ignore_errors=True)


if __name__ == "__main__":
    main()
