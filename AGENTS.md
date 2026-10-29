# Agent Instructions

Insight Workbench is an IntelliJ Platform plugin written in Kotlin (Java 21, Gradle, JUnit 4). See [README.md](README.md) for features and entry points.

## Build and test

Use `./build.sh` for compile, test, and packaging. Don't call `./gradlew` or `./docker.sh` directly; Docker isn't available in agent containers.

| Goal | Command | Typical time (warm) |
| --- | --- | --- |
| Compile main and test sources | `./build.sh compile` | ~15 s up-to-date, ~1.5 min full |
| Run one test class or pattern | `./build.sh test --tests 'dev.insight.chat.*'` | depends on scope |
| Run all unit tests | `./build.sh test` | ~2 min |
| Package and verify the plugin | `./build.sh build` | |
| Tests, package, verify (before finishing) | `./build.sh check` | |
| Any other Gradle task | `./build.sh gradle <tasks...>` | |

`./build.sh help` lists commands; extra arguments pass through to Gradle.

### Why build.sh

- **The IntelliJ IDEA Ultimate SDK, Python/Go plugins, and dependencies are cached** in `.gradle/agent-home/`, shared across agent images and container restarts. Don't delete it, change `GRADLE_USER_HOME`, or pass `-g`/`--gradle-user-home`; that would download several GB again.
- A separate project cache (`.gradle/agent-project/`) avoids locking the host IDE's cache.
- Builds serialize with `./docker.sh` and other agents via `.gradle/build.lock`. If it prints `waiting...`, let the current build finish; don't kill it.
- Builds run without a Gradle daemon because daemons in separate containers can hold cache locks and time out.

### Working efficiently

- Use the narrowest command while editing: `./build.sh compile`, then `./build.sh test --tests '<Class or pattern>'` for affected tests. Run `./build.sh check` at the end.
- Add `--offline` when dependencies haven't changed to avoid downloads.
- Avoid `--rerun-tasks`, `clean`, and `--refresh-dependencies` unless diagnosing a build issue; incremental builds and up-to-date tests are faster.
- `./build.sh verify` (`verifyPlugin`) downloads another full IDE (IntelliJ IDEA Ultimate 2025.3.6); use it only for binary compatibility checks.
- `runIde` requires a display and won't work in agent containers.
- Reports: `build/reports/tests/test/index.html`; per-class XML: `build/test-results/test/`. Find failures with `grep -l '<failure' build/test-results/test/*.xml`.
- Tests run headless. Swing and Java2D use fonts already installed in the agent image.

## Project layout

- `src/main/kotlin/dev/insight/`: plugin sources by feature (`chat`, `codereview`, `diff`, `callgraph`, `entrypoints`, ...).
- `src/main/resources/META-INF/`: `plugin.xml` and optional per-language descriptors (`call-graph-*.xml`, `diff-git.xml`).
- `src/test/kotlin/dev/insight/`: JUnit 4 and IntelliJ Platform tests mirroring the main package structure.
- `flows/`: design notes for the larger features.
- `build.gradle.kts`: target platform (built against IU 2025.2.4, `sinceBuild` 252, `untilBuild` 262.*).

## LLM workflow design

- Inject `CompletionFn` and `DecisionFn` and any tools, evidence providers, or caches into workflow constructors. Keep client configuration, IDE reads, and Swing updates in service/UI adapters so workflows can run with fake ports in plain JUnit tests.
- Pass input and progress callbacks to workflow methods; retain descriptive entry points such as `summarize`, `find`, or `run`.
- Keep prompt construction, source-range calculations, request estimates, and HTML rendering in non-Swing files. Test these functions without `BasePlatformTestCase`; reserve platform tests for IDE integration.
- Compose tool policies with named decorators in the `chat` package, alongside `ChatTool`, rather than embedding anonymous wrappers in workflows.
