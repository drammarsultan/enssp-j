# Illustrative evaluation

This directory reproduces the illustrative example of the enSSP-J paper.

## Subjects

| Class | Source | Notes |
|---|---|---|
| `sequences.Stack` | Symbolic PathFinder examples | linked stack |
| `sequences.BinTree` | Visser et al., ISSTA 2006 (via SPF) | binary search tree |
| `rbt.TreeMap` | Visser et al., ISSTA 2006 (via SPF) | red-black tree derived from `java.util.TreeMap` |
| `org.apache.commons.collections4.queue.CircularFifoQueue` | Commons Collections 4.4 | bounded FIFO buffer |
| `org.apache.commons.collections4.map.Flat3Map` | Commons Collections 4.4 | flat storage for ≤3 entries, then delegates to a hash map |
| `org.apache.commons.collections4.map.LRUMap` | Commons Collections 4.4 | bounded map with least-recently-used eviction |
| `org.apache.commons.collections4.list.TreeList` | Commons Collections 4.4 | list backed by an AVL tree |

Coverage and mutation analysis consider the class together with its nested classes.

## Configurations

* `enssp`: enSSP-J with default settings (GA and reduction)
* `enssp-noreduce`: enSSP-J without reduction
* `random`: random event sequences, same budget, abstraction and archive rule
* `ssp`: plain SSP, one test per sensitivity target, no GA and no reduction
* `evosuite`: EvoSuite 1.2.0, default configuration, same search budget,
  `no_runtime_dependency=true`

## Running

```sh
../build.sh
../examples/subjects/build-subjects.sh
./setup-tools.sh                                   # PIT, JaCoCo, JUnit, EvoSuite
JAVA8=/path/to/jdk8/bin/java python3 run_experiments.py --tools tools --seeds 5 --budget 30 \
    --configs enssp,enssp-noreduce,random,ssp,evosuite
python3 summarize.py results/runs.csv --out results
```

`run_experiments.py` appends one row per run to `results/runs.csv` and resumes where it
stopped. For every run it:

1. generates the suite,
2. compiles it and runs it under the JaCoCo agent (line and branch coverage),
3. runs PIT 1.17.0 with its default mutators on the class under test and its nested classes.

Failures are logged in `results/failures.log` and never silently dropped.
`results/` in this repository contains the raw `runs.csv` and the summaries used in the paper.
