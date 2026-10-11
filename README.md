# Insight Workbench

An initial Kotlin IntelliJ IDEA plugin with a chat sidebar and a visualization dashboard in the main editor area. Built with native Swing/IntelliJ components; no browser runtime or frontend build is required.

## Run

Requires JDK 21. Open this directory as a Gradle project in IntelliJ IDEA and use JDK 21 as the Gradle JVM, or run:

```sh
./gradlew runIde
```

In the development IDE, open a project, then select **Insight Chat** on the right tool window bar. Click **Open dashboard**, or use **Tools → Open Insight Dashboard**. The dashboard opens as a normal editor tab alongside your code. You can expand the work area using IntelliJ's existing **Distraction Free Mode** or **Full Screen** actions.

The sidebar supports multiline messages, Ctrl+Enter / Cmd+Enter to send, and **New chat**. The dashboard includes a 7/14-day activity chart, coverage bars, a module relationship graph, and a selectable module list. All values and assistant responses are explicitly demo content.

## Build and check

```sh
./gradlew test buildPlugin verifyPluginStructure
```

The installable ZIP is written to `build/distributions/`. Install it using **Settings → Plugins → gear → Install Plugin from Disk**. The compatibility range is IntelliJ IDEA 2025.2–2026.2 (`252`–`262.*`), compiled against Community 2025.2.4 (the lowest supported platform, Java 21). Change the target and compatibility bounds in `build.gradle.kts` as you validate additional IDE versions.

For binary API compatibility checks (downloads additional IDEs):

```sh
./gradlew verifyPlugin
```

## Docker build and test

Requires Docker with BuildKit (Docker Desktop or Docker Engine with Buildx). Run these commands from the project root. The image supplies JDK 21 and uses the checked-in Gradle wrapper. The first build needs internet access to download Gradle, dependencies, and IntelliJ IDEA; allow several GB of disk space. Later builds reuse a Gradle cache.

### Shell script

On Linux or WSL, use the executable `docker.sh` helper. It runs Docker through `sudo`, builds the development image using Docker's layer cache, and mounts the current project for Gradle tasks. Run the script as your regular user: `sudo docker run` uses your UID/GID so build output belongs to you. The Gradle distribution, dependencies, and the IntelliJ IDEA SDK (about 1.4 GB to download, several GB unpacked) are cached in the project's `.gradle/` directory, so only the first run downloads them; delete `.gradle/` to force a fresh download. Requires Bash and `sudo` in addition to Docker.

```sh
./docker.sh help             # Show commands and usage.
./docker.sh image            # Build only the development image.
./docker.sh compile          # Compile the code.
./docker.sh test             # Run unit tests.
./docker.sh build            # Package the plugin and verify its structure.
./docker.sh check            # Test, package, and check structure (also the default).
./docker.sh verify           # Check binary compatibility with selected IDEs.
./docker.sh clean            # Remove build output.
./docker.sh tasks            # List Gradle tasks.
./docker.sh test --rerun-tasks
./docker.sh gradle clean test buildPlugin verifyPluginStructure
./docker.sh export           # Export the tested ZIP and reports to docker-artifacts/.
```

Gradle output is written to `build/`; the plugin ZIP is under `build/distributions/` and the HTML test report is `build/reports/tests/test/index.html`. The `export` command runs the same checks in the container, reusing that cache, and then copies the ZIP and reports to `docker-artifacts/`. The helper also works when invoked from another directory because it locates the project relative to the script.

### Direct Docker commands

Build the code, run unit tests, package the plugin, and check its structure, then export the ZIP and reports to your machine:

```sh
docker build --target artifacts --output type=local,dest=./docker-artifacts .
```

The installable plugin is `docker-artifacts/distributions/insight-workbench-0.1.0.zip`. The HTML test report is `docker-artifacts/reports/tests/test/index.html`, and JUnit XML results are under `docker-artifacts/test-results/test/`. Any failing build, test, or structure check fails the Docker build. The default Dockerfile target is `artifacts`, which contains exported files only.

For repeated development tasks, build the runnable `development` image and create a persistent Gradle cache:

```sh
docker build --target development -t insight-workbench-dev .
docker volume create insight-workbench-gradle

# Run the default checks against the source copied into the image.
docker run --rm \
  --mount type=volume,source=insight-workbench-gradle,target=/root/.gradle \
  insight-workbench-dev
```

Bind-mount the project to test current edits and keep build output on the host. The following examples use a POSIX shell (Linux, macOS, or WSL):

```sh
# Compile the Kotlin code.
docker run --rm \
  --mount type=bind,source="$(pwd)",target=/workspace \
  --mount type=volume,source=insight-workbench-gradle,target=/root/.gradle \
  insight-workbench-dev compileKotlin

# Run unit tests; reports appear in build/reports/tests/test/.
docker run --rm \
  --mount type=bind,source="$(pwd)",target=/workspace \
  --mount type=volume,source=insight-workbench-gradle,target=/root/.gradle \
  insight-workbench-dev test

# Package the extension and check its structure; ZIPs appear in build/distributions/.
docker run --rm \
  --mount type=bind,source="$(pwd)",target=/workspace \
  --mount type=volume,source=insight-workbench-gradle,target=/root/.gradle \
  insight-workbench-dev buildPlugin verifyPluginStructure

# Check binary compatibility with the IDE versions selected by Gradle.
# This downloads additional IDEs and can take considerably longer.
docker run --rm \
  --mount type=bind,source="$(pwd)",target=/workspace \
  --mount type=volume,source=insight-workbench-gradle,target=/root/.gradle \
  insight-workbench-dev verifyPlugin

# Clean and rerun all standard checks together.
docker run --rm \
  --mount type=bind,source="$(pwd)",target=/workspace \
  --mount type=volume,source=insight-workbench-gradle,target=/root/.gradle \
  insight-workbench-dev clean test buildPlugin verifyPluginStructure

# List other available Gradle tasks.
docker run --rm insight-workbench-dev tasks --all
```

On Linux, bind-mounted output is owned by the container's root user. To write it as your own user, add `--user "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/workspace/.gradle` to the bind-mounted commands and omit the `/root/.gradle` volume mount; Gradle will use the project's local cache instead.

The container workflow runs without a display. To test the plugin UI, run `./gradlew runIde` on a host with JDK 21 and a desktop session, or install the exported ZIP using **Settings → Plugins → gear → Install Plugin from Disk**. Binary compatibility checks and unit tests do not replace this manual UI check.

To remove the development image and its dependency cache when finished:

```sh
docker image rm insight-workbench-dev
docker volume rm insight-workbench-gradle
```

Docker references: [multi-stage builds and targets](https://docs.docker.com/build/building/multi-stage/) and [local artifact export](https://docs.docker.com/build/exporters/local-tar/).

## Call graph

Put the caret in a Java or Kotlin function (or on a call to it) and choose **Show Call Graph** from the editor context menu, the Structure view context menu, or **Tools**. A tab opens in the editor area with callers on the left of the selected function and callees on the right. Use the toolbar to choose direction, depth (1–5), and whether library calls are shown. Double-click a node to open its source; select a node and use **Focus selected** to explore from it. Searches run in the background with a progress indicator, wait for indexing to finish, and stop at 150 functions.

How it is built, and the platform APIs it relies on:

- **Callers** (`CallGraphAnalyzer.callers`): `ReferencesSearch` over `GlobalSearchScope.projectScope`. Each reference is mapped to its enclosing function. This is language neutral; with the Kotlin plugin installed it finds Java→Kotlin and Kotlin→Java usages. References in comments and imports are skipped.
- **Callees** (`CallGraphAnalyzer.callees`): a PSI walk of the function body. A small language extension resolves each call site: `PsiCall.resolveMethod()` for Java, and `KtCallElement`/operator/callable references resolved through PSI references for Kotlin.
- **Language support** is the `dev.insight.callGraphLanguageSupport` extension point, registered from optional `<depends>` descriptors (`META-INF/call-graph-java.xml`, `call-graph-kotlin.xml`). The plugin still loads in IDEs without Java or Kotlin. Add another language by implementing `CallGraphLanguageSupport`.
- **Kotlin K2 mode**: the `supportsKotlinPluginMode supportsK2="true"` declaration must be in the main `plugin.xml`. In K2 mode (the default) the Kotlin plugin skips the optional `call-graph-kotlin.xml` if the declaration is only there, and Kotlin functions then get no callees. `PluginDescriptorTest` guards this.
- **Tests**: `JavaCallGraphTest` and `KotlinCallGraphTest` run the analyzer on real PSI. The Java test uses the JDK running the tests, because the default light-fixture `mockJDK-1.7` is not shipped with the IDE distribution used for plugin tests.
- **Threading**: `withBackgroundProgress` plus one `smartReadAction` per node expansion, so the UI stays responsive and the search never holds a long read lock.
- **Rendering**: `CallGraphLayout.kt` (pure layered layout with crossing reduction, unit tested) and `CallGraphCanvas.kt` (Java2D, same approach as the dashboard charts).

Known limitations: calls made through an overridden or interface method are attributed to the declaration the code resolves to (overriders are not expanded); calls in field or property initializers are not attributed to a function; library functions are shown as leaves; Kotlin implicit constructors and property getters without a body are not nodes.

Other SDK options evaluated:

- **Call Hierarchy** (`LanguageCallHierarchy`, `CallHierarchyProvider`, Java/Kotlin `CallerMethodsTreeStructure`): this is what **Navigate → Call Hierarchy** uses. The tree structures are built by the hierarchy browser (`createHierarchyTreeStructure` is protected), so they are not a convenient API for feeding a custom graph. They also show a tree, not a graph.
- **UAST** (`UMethod`, `UCallExpression.resolve()`): a single implementation could cover Java, Kotlin, Groovy, and Scala. It is a good replacement for the two language extensions if more JVM languages are needed.
- **Kotlin Analysis API** (`analyze { … }`): exact K2 resolution, including implicit invokes and property accessors. Use it if the Kotlin PSI reference approach proves insufficient.
- **Diagrams API** (`com.intellij.diagram`): ships only with IntelliJ IDEA Ultimate, so it is not usable with the Community target.

## Extend

- `chat/ChatBackend.kt`: provider interface and local demo implementation. Inject your real backend in `ChatService`; backend calls already run on IO, with updates on the EDT. Add streaming, cancellation, authentication, and provider configuration here as needed.
- `chat/ChatService.kt`: per-project conversation shared by sidebar views. History is kept in memory for the project session. Listeners are detached when content is disposed; the platform cancels the service coroutine scope when the project closes.
- `dashboard/DashboardEditor.kt`: custom editor provider and project-scoped virtual file. Opening the dashboard repeatedly reuses the same tab.
- `dashboard/DashboardPanel.kt`: dashboard layout and interactions.
- `dashboard/Charts.kt`: theme-aware Java2D renderers; add new views or replace them with a chart library as the visualization requirements become clearer.
- `dashboard/DemoData.kt`: replace this sample source with an analysis service that collects data in background tasks and publishes immutable snapshots to the UI.
- `META-INF/plugin.xml`: tool window, editor provider, and action registration.
- `callgraph/`: call graph action, analysis, layout, and editor tab (see **Call graph** above).

The call graph reads project source locally through the IDE PSI and indexes; no project source is transmitted. A real model integration and real project analysis are future implementation work. This scaffold targets a local IDE; remote development/split mode needs separate design and validation.

JetBrains references: [quick start](https://plugins.jetbrains.com/docs/intellij/plugins-quick-start.html), [tool windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html), [Gradle platform plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), and [Kotlin support](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html).
