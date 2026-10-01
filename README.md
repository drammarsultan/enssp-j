# enSSP-J

**enSSP-J** generates JUnit 4 test suites for *stateful* Java classes (containers, buffers,
caches, parsers, anything whose behaviour depends on its internal state) using **enhanced
state-sensitivity partitioning (enSSP)**:

1. **State variables.** Every instance field of the class under test, every *pure observer*
   (a zero-argument method that never changes the object, detected automatically), the number
   of internal objects reachable from it (tree/list nodes), and shape variables of those
   objects (e.g. how many nodes have no left child). Numbers are abstracted to the buckets
   `<0, 0, 1, …, bound−1, ≥bound`.
2. **System IDs.** Every distinct combination of abstract state-variable values gets a unique
   integer ID.
3. **Access programs and events.** Public methods and constructors, instantiated with values
   from an argument pool (`-1, 0, 1, 2, 3, 5`; `""`, `"a"`, `"b"`; booleans; enum constants).
   Parameters typed `Object`/`Comparable` receive boxed integers, so generic containers work.
4. **Sensitivity partitioning.** A bounded breadth-first exploration probes every discovered
   data state with every event and classifies the response (void, value, null, true, false or
   exception type, and whether the state changed). For each access program, states that respond
   identically are *equally sensitive* to it. This yields the **sensitivity targets**
   `(access program, sensitivity class, response class)`.
5. **Genetic optimisation of event sequences.** A GA evolves event sequences (cut-and-splice
   crossover, insert/delete/replace mutation, 20% random immigrants per generation) whose fitness
   rewards uncovered targets and newly visited data states and penalises redundant data states and
   length. Every sequence that covers a new target joins the archive.
6. **Reduction.** State cycles that revisit a system ID are cut out, and whole sequences are
   removed by greedy set cover, both only when no target is lost. Every candidate is re-executed.
7. **JUnit emission.** One test per sequence, with regression assertions on every returned
   value, `assertTrue(raised)` checks for expected exceptions, and assertions on every observer at
   the end of the test.

enSSP-J also provides two baselines for comparison: `--mode random` (random event sequences under
the same budget and archive rule) and `--mode ssp` (plain SSP: one test per sensitivity target,
no GA, no reduction).

## Requirements

* JDK 11 or newer to build and run enSSP-J. It has **no runtime dependencies**.
* JUnit 4.13+ on the test classpath of the project that will run the generated tests.

## Build

```sh
mvn package            # or, without Maven:
./build.sh
```

Both produce `target/enssp-j-1.0.0.jar`.

## Usage

```sh
java -jar target/enssp-j-1.0.0.jar --class com.example.MyQueue --cp path/to/classes --out src/test/java
```

| Option | Default | Meaning |
|---|---|---|
| `--class` | (required) | Fully qualified name of the class under test (concrete, public) |
| `--cp` | empty | Classpath of the class under test (`:` or `;` separated) |
| `--out` | `enssp-tests` | Output directory; the test goes into the class's package folder |
| `--mode` | `enssp` | `enssp`, `random` or `ssp` |
| `--no-reduce` | off | Skip reduction (enssp mode) |
| `--budget` | 30 | Total time budget in seconds (exploration + search) |
| `--seed` | 1 | Random seed |
| `--bound` | 3 | Numeric abstraction bound |
| `--max-states` | 150 | Maximum data states probed for sensitivity |
| `--max-depth` | 5 | Depth of the breadth-first exploration |
| `--max-length` | 20 | Maximum events per sequence |
| `--population` | 50 | GA population size |
| `--immigrants` | 0.2 | Fraction of each generation replaced by random sequences |
| `--stagnation` | off | Stop after *n* evaluations without a new target |
| `--suffix` | `_ESSP_Test` | Test class name suffix |
| `--report` | none | Write the JSON run report to a file |

A JSON report is always printed to standard output, for example:

```json
{
  "class": "sequences.BinTree",
  "mode": "enssp",
  "accessPrograms": 3,
  "events": 18,
  "systemIds": 10,
  "partitions": 10,
  "sensitivityClasses": 29,
  "targets": 58,
  "targetsCovered": 58,
  "tests": 18,
  "eventsInSuite": 199,
  ...
}
```

Search stops when every known target is covered or the budget is spent. Output printed by the
class under test is suppressed so that the report stays machine-readable.

## Example

```sh
./build.sh
examples/subjects/build-subjects.sh          # builds the example subjects
java -jar target/enssp-j-1.0.0.jar --class rbt.TreeMap --cp examples/subjects/classes --out /tmp/t
```

## Tests

```sh
mvn test                                              # or
JUNIT_CP=junit-4.13.2.jar:hamcrest-core-1.3.jar ./test.sh
```

The end-to-end tests run every mode on a fixture class, compile the generated suite with
`javax.tools` and run it with JUnit.

## Reproducing the illustrative evaluation

See [`experiments/README.md`](experiments/README.md).

## Limitations

* Only public, concrete classes with at least one public constructor whose parameters are
  primitives, boxed types, `String`, `Object`/`Comparable`/`Number`, or enums. Methods with other
  parameter types (collections, functional interfaces, user types) are skipped.
* Oracles are regression oracles captured from the version under test. They detect behavioural
  changes, not violations of a specification. A class whose state cannot be observed through
  public methods yields weak oracles.
* Abstraction is fixed-bound. States that differ only beyond the bound, or in data ordering
  (for example LRU order), share a system ID.
* The class under test runs in the enSSP-J JVM. Sequences are time-limited (1 s each), but the
  observer-detection phase calls methods directly, so a class that never returns from a method
  can hang the tool.

## Licence

MIT (see `LICENSE`). The example subjects are third-party code under Apache-2.0; see
`examples/subjects/NOTICE`.

## Citing

See `CITATION.cff`.
