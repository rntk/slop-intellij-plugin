# Insight Workbench

An initial Kotlin IntelliJ IDEA plugin with a chat sidebar and a visualization dashboard in the main editor area. Built with native Swing/IntelliJ components; no browser runtime or frontend build is required.

## Run

Requires JDK 21. Open this directory as a Gradle project in IntelliJ IDEA and use JDK 21 as the Gradle JVM, or run:

```sh
./gradlew runIde
```

In the development IDE, open a project, then select **Insight Chat** on the right tool window bar. Click **Open dashboard**, or use **Tools → Open Insight Dashboard**. The dashboard opens as a normal editor tab alongside your code. You can expand the work area using IntelliJ's existing **Distraction Free Mode** or **Full Screen** actions.

The sidebar supports multiline messages, Ctrl+Enter / Cmd+Enter to send, **Stop**, and **New chat**. Without a configured LLM server, assistant responses are demo content; see **LLM chat (llama.cpp)** to connect one. The dashboard includes a 7/14-day activity chart, coverage bars, a module relationship graph, and a selectable module list. All dashboard values are explicitly demo content.

## Build and check

```sh
./gradlew test buildPlugin verifyPluginStructure
```

The installable ZIP is written to `build/distributions/`. Install it using **Settings → Plugins → gear → Install Plugin from Disk**. The compatibility range is IntelliJ IDEA 2025.2–2026.2 (`252`–`262.*`), compiled against IntelliJ IDEA Ultimate 2025.2.4 (the lowest supported platform, Java 21) plus the PythonCore and Go Marketplace plugins at the same build. Ultimate is needed only to compile the Go and JavaScript/TypeScript support; every language is an optional dependency, so the ZIP also installs in Community, PyCharm, GoLand, WebStorm and other IntelliJ-based IDEs. Change the target and compatibility bounds in `build.gradle.kts` as you validate additional IDE versions.

For binary API compatibility checks (downloads additional IDEs):

```sh
./gradlew verifyPlugin
```

## Docker build and test

Requires Docker with BuildKit (Docker Desktop or Docker Engine with Buildx). Run these commands from the project root. The image supplies JDK 21 and uses the checked-in Gradle wrapper. The first build needs internet access to download Gradle, dependencies, and IntelliJ IDEA; allow several GB of disk space. Later builds reuse a Gradle cache.

### Shell script

On Linux or WSL, use the executable `docker.sh` helper. It runs Docker through `sudo`, builds the development image using Docker's layer cache, and mounts the current project for Gradle tasks. Run the script as your regular user: the build container uses your UID/GID so build output belongs to you. Requires Bash, `sudo`, `flock` (util-linux), and `cksum` (coreutils) in addition to Docker.

The Gradle distribution, dependencies, and IntelliJ IDEA SDK (about 1.4 GB to download, several GB unpacked) are stored in a persistent Docker volume named `insight-workbench-gradle-<UID>-<project-path-checksum>`. The helper prints its name on each run. [Docker volumes](https://docs.docker.com/engine/storage/volumes/) survive container removal, so downloads are reused. The first run after switching from the old `.gradle/docker/` cache downloads dependencies again; that old cache is left untouched and is no longer used by the helper. To reset the new cache, finish all builds and run `sudo docker volume rm <printed-volume-name>`.

IntelliJ can remain open with its normal Gradle user home (usually `~/.gradle`). Docker's global cache and writable Java home live in the volume, and its project cache uses `.gradle/docker-project/`, separate from IntelliJ's `.gradle/` project cache. Concurrent helper invocations wait on a host lock because separate containers cannot coordinate [Gradle cache locks](https://docs.gradle.org/current/userguide/dependency_caching.html#sec:cache_locking). Source files and `build/` output remain shared, so avoid simultaneous host and Docker compilation or cleaning.

Earlier versions could report lock timeouts naming a host daemon such as PID 1092 in `.gradle/docker/caches/`, even with IntelliJ closed: Gradle daemons can outlive the command that started them. The current helper bypasses that directory entirely. To retire an old host daemon, wait for its build to finish and run `./gradlew -g "$PWD/.gradle/docker" --stop` on the host. Do not delete lock files while Gradle is running. `Picked up JAVA_TOOL_OPTIONS: -Duser.home=...` and the single-use daemon message are expected: the script supplies a writable Java home directory for container processes, and `--no-daemon` can still fork a temporary JVM.

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

On Linux, bind-mounted output is owned by the container's root user. To write it as your own user, use `./docker.sh`, which sets your UID/GID, a writable home for forked Java processes, and separate Docker Gradle caches.

The container workflow runs without a display. To test the plugin UI, run `./gradlew runIde` on a host with JDK 21 and a desktop session, or install the exported ZIP using **Settings → Plugins → gear → Install Plugin from Disk**. Binary compatibility checks and unit tests do not replace this manual UI check.

To remove the development image and its dependency cache when finished:

```sh
docker image rm insight-workbench-dev
docker volume rm insight-workbench-gradle
```

Docker references: [multi-stage builds and targets](https://docs.docker.com/build/building/multi-stage/) and [local artifact export](https://docs.docker.com/build/exporters/local-tar/).

## Application graph

Open **View → Tool Windows → Application Graph** to see the whole project (Java, Kotlin, Python, Go, JavaScript, TypeScript), including disconnected declarations. File, class, function, property, parameter, local variable, and type alias nodes are connected by containment, imports, inheritance, calls, and resolved symbol references.

Large projects are shown with progressive disclosure. The graph is a tree of groups — directories (relative to the deepest directory shared by all files, with file-less chains such as `main/kotlin/dev` merged), files, classes, and their members — and every hidden node is folded into its nearest shown group. Collapsed groups look like a stack of cards with a **+N** badge counting what they contain; expanded groups (**−**) stay on the graph and are linked to their members by containment edges. All relationships between two shown nodes merge into a single edge, drawn as the strongest kind (inheritance, then call, reference, import) and thicker the more relationships it stands for. Users are placed left of what they depend on.

Click or right-click a node for **Side by side**, **Expand**, **Collapse**, **Expand all inside**, **Collapse into** its parent group, and **Open source**. Select a group and press Enter to expand or collapse it; Enter on another symbol opens its declaration. **Detail** starts over from **Overview** (expands groups level by level, smallest first, while about 40 nodes fit), **Files** (all directories expanded), or **Symbols** (everything expanded). Expanded groups are kept when the graph rebuilds after edits, and changing detail reuses the last analysis.

**Side by side** opens a source-flow editor tab for that exact symbol with its nearest few callers and called functions (3 each, breadth first; `SIDE_BY_SIDE_LIMIT`), so a busy function does not open dozens of columns: callers are columns to the left (farthest caller first), called functions to the right, and the tab opens scrolled to the symbol itself (outlined). A function that both calls and is called by the symbol (recursion) appears once, with the callees. A file, class, or directory starts with its contained functions and call-owning declarations. Each function appears once in a numbered column, ordered by caller depth and call depth, with its full source file in a read-only native IntelliJ editor using the IDE's syntax colours and line numbers. The function's code is highlighted and centred; each column scrolls independently. **← Called by** and **Calls →** links jump to the corresponding column, including shared callees and recursive calls; **+ Show N more callers / called functions** adds the next few direct ones next to the column. Explore further from the source itself: Ctrl/Cmd+click a function call (or declaration) to show that function next to the column, or right-click it to show its callers or the functions it calls in the current view, or to open it, its callers, or its calls side by side in a new tab so the current view stays small. **Close** removes a column and **New tab** reopens a column's function in a fresh tab. Use the function picker or **Previous/Next** to move through the columns, or **Open source** to edit the original file. Only nearby columns allocate editors. Source documents reflect current unsaved contents; call relationships are a snapshot, so reopen the view after changing the flow. Graph filters and collapsed groups do not remove functions from the selected node's chain.

**View → Sections** arranges the shown nodes in titled columns, like a service blueprint, instead of the free layered layout. Choose what a column stands for with **by**: **File** or **Folder** (no enrichment needed), or **Domain**, **Layer**, **Phase**, or **Importance** after **Enrich**. Columns run left to right with users before what they use (enrichment taxonomies keep their own order), and inside a column nodes form sub-columns by dependency, wrapping after 12 rows. A banner names the project and grouping with section, symbol and cross-section call counts. Each column has a title band and an annotation: the LLM group label and summary for files and folders, the domain, layer, phase, or importance description otherwise, plus facts such as symbol and file counts, entry points, calls in and out of the section, and the dominant domain and layer. Columns are tinted with their domain, layer, or importance colour (for files and folders, the dominant **Colour** value). Expanded files and folders are represented by their columns rather than drawn as nodes; a collapsed folder gets a column of its own. The footer lists the main flows, or the busiest calls between sections without enrichment. Hover a column header for its full annotation. Node decorations, filters, and flow spotlighting work as in the graph view.

The **Flow Chart** tab in the **Application Graph** tool window has its own canvas and controls for exploring calls, read top to bottom like a timeline. Its toolbar provides Colour, enriched Flow selection, Add flow, step navigation, zoom, Show/Hide calls, Show/Hide callers, Remove column and Clear chart. Search chart highlights matching visible steps with the same fuzzy name matching as the graph; Previous/Next and Enter/Shift+Enter jump between them. Each panel keeps its own selection, search and zoom, and shares Refresh, Enrich and Report through the same analysis. Right-click a function in the graph view and choose **Add to flow chart**, right-click a symbol in the editor or the Structure view and choose **Add to Flow Chart** (inside a parameter or local variable this adds the declaration around it; a symbol written moments ago is added once the graph has rebuilt), or choose an enriched flow in **Flow**: each becomes a column of its own, headed by a banner, with its direct calls shown; add as many as you like side by side. Every step on the chart can be explored in both directions on the same canvas: the pill below a step shows or hides what it calls (inside a frame headed by the pill, in evaluation order), and the pill above it shows or hides its direct in-project callers, side by side and joining into the function. This works for callers too, so you can open a caller's own calls or callers, and so on up and down the call graph. A caller pill is left out while the only caller is the step the function is drawn in. Right-click a step for **Open source**, **Show in graph**, **Show/Hide calls**, **Show/Hide callers**, **Open in new column** (the function then heads its own column and is a reference elsewhere), **Remove column** and **Clear chart**. A function appears once as a card; elsewhere it shows **↺ step N** (click to jump). Steps are numbered in reading order across columns, callers before the function they call. Every in-project call is retained, including utilities and error paths, so expanding compact subflows exposes the underlying details. The chart is kept, by function, when the graph rebuilds after edits. Steps on a column's main spine alternate left and right; each card shows the step number, file and line, the name, the first paragraph of its doc comment, and the layer, domain and concerns from enrichment, with the dot coloured by **Colour**. The source of each step is read in the background as it appears: calls under `if`/`elif`/`else` (`else if` chains count as one decision), `when`/`switch`/`match` cases, the `?:` operator and `a if c else b` become a decision that forks into side-by-side branch columns and joins again below; `x == "a"`/`x == "b"` chains ask `x?`; a branch without calls is drawn as a dashed skip path. Calls in `catch`/`except` become a red **on error** branch, loops get a dashed frame, and a base method that runs one of several implementations forks per implementation. Click a step to select it, double-click or Enter to open its source, Up/Down or **◀ ▶** to step through, Left to toggle callers, Right or Space to toggle calls, Delete to remove a column, Ctrl/Cmd + wheel to zoom. Control structures are recognised from PSI class names (`PsiIfStatement`, `KtWhenEntry`, `PyExceptPart`, `GoForStatement`…) and their labels from source text, so this works for every supported language without language-specific code (`CallSites.kt`, tested in `CallSitesTest`). Chart building and layout are PSI-free (`FlowChart.kt`, `FlowChartLayout.kt`, tested in `FlowChartTest`).

**Compact subflows** starts enabled in the Flow Chart tab: consecutive source calls within the same control branch fold into expandable cards. Click a card to inspect its calls, right-click for source evidence, or use **Collapse subflows** to return to compact detail. **Summarize subflows** uses the existing LLM server to label and group bounded sequences (up to 24 blocks per click); **Stop summary** cancels it. Model membership and ordering are validated against exact source call occurrences. Inferred labels are marked **LLM interpretation**; failures retain structural groups. Search includes visible subflow labels. The call graph editor’s **Explore flow** button opens the selected function in this view. After **Enrich**, functions also carry bottom-up LLM summaries: compact cards show a short action label such as “Store user data” (the code name stays in the card’s meta line), helpers summarized as logging or plumbing are folded into a “+N helper calls folded” count unless they reach storage or network code, and a newly placed function opens down its main path to about 24 items, leaving one-step operations closed for drill-down. Storage, network and throw effects on cards come from code rules, not the model. Turn off **Compact subflows** to see every call with code names. See [the flow chart design](flows/flowchart.md) for grounding and current scope.

Opening or switching source files fills the nodes holding that file’s symbols (a collapsed directory counts) and outlines their direct dependencies and users, fading the rest of the graph so they stand out. **Current file** expands the groups around the active file and scrolls to it. Drag the graph to pan, or click it and use the arrow keys (Shift for faster movement). The scroll wheel zooms around the cursor; the toolbar zooms around the viewport centre. Imports use dashed edges and references use dotted edges.

The graph rebuilds after PSI changes with a short debounce, runs in a cancellable background smart read action, and waits for indexing. It covers project content without the function view’s depth/node limits. External library symbols and unresolved references are omitted. Relationships reflect static resolution; runtime dispatch, reflection, and wildcard imports to packages are not expanded.

## Call graph

Put the caret in a function in any supported language (or on a call to it) and choose **Show Call Graph** from the editor context menu, the Structure view context menu, or **Tools**. A tab opens in the editor area with callers on the left of the selected function and callees on the right. Use the toolbar to choose direction, depth (1–5), and whether library calls are shown. Double-click a node to open its source; select a node and use **Focus selected** to explore from it. Searches run in the background with a progress indicator, wait for indexing to finish, and stop at 150 functions.

How it is built, and the platform APIs it relies on:

- **Callers** (`CallGraphAnalyzer.callers`): `ReferencesSearch` over `GlobalSearchScope.projectScope`. Each reference is mapped to its enclosing function. This is language neutral; with the Kotlin plugin installed it finds Java→Kotlin and Kotlin→Java usages. References in comments and imports are skipped.
- **Callees** (`CallGraphAnalyzer.callees`): a PSI walk of the function body. A small language extension resolves each call site: `PsiCall.resolveMethod()` for Java; `KtCallElement`/operator/callable references for Kotlin; `PyCallExpression.multiResolveCalleeFunction` for Python (class calls map to `__init__`); `GoCallExpr` callee references for Go; `JSCallExpression`/`new` for JavaScript and TypeScript (classes map to their constructor).
- **Language support** is the `dev.insight.callGraphLanguageSupport` extension point, registered from optional `<depends>` descriptors (`META-INF/call-graph-java.xml`, `-kotlin`, `-python`, `-go`, `-javascript`). Each is active only when the IDE has that language plugin, so the same artifact works in IntelliJ IDEA, PyCharm, GoLand, WebStorm, etc. Add another language by implementing `CallGraphLanguageSupport`.
- **Kotlin K2 mode**: the `supportsKotlinPluginMode supportsK2="true"` declaration must be in the main `plugin.xml`. In K2 mode (the default) the Kotlin plugin skips the optional `call-graph-kotlin.xml` if the declaration is only there, and Kotlin functions then get no callees. `PluginDescriptorTest` guards this.
- **Tests**: `JavaCallGraphTest`, `KotlinCallGraphTest`, `PythonCallGraphTest`, `GoCallGraphTest`, and `JavaScriptCallGraphTest` run the analyzer and the application graph on real PSI. The Java test uses the JDK running the tests, because the default light-fixture `mockJDK-1.7` is not shipped with the IDE distribution used for plugin tests. PythonCore loads in tests only together with the bundled `org.toml.lang` plugin. The Go test needs no Go SDK: it turns off Go modules integration and uses a GOPATH folder inside the test project so cross-package imports resolve.
- **Threading**: `withBackgroundProgress` plus one `smartReadAction` per node expansion, so the UI stays responsive and the search never holds a long read lock.
- **Rendering**: `CallGraphLayout.kt` (pure layered layout with crossing reduction, unit tested) and `CallGraphCanvas.kt` (Java2D, same approach as the dashboard charts).

Known limitations: calls made through an overridden or interface method are attributed to the declaration the code resolves to (overriders are not expanded); calls in field or property initializers are not attributed to a function; library functions are shown as leaves; Kotlin implicit constructors and property getters without a body are not nodes.

Language notes: Go imports resolve to package directories, so an import links to every file in that directory. Go interface methods are function nodes, so calls through interfaces stay visible. JavaScript/TypeScript treat variables, fields, and object properties that hold an arrow or function expression as functions; anonymous callbacks belong to the enclosing function. CommonJS `exports.f = …` assignments are not nodes, and `require()` gives only a file-level import edge. Python attributes assigned via `self.x = …` are properties of the class; repeated assignments to one name give one node. Dynamic calls (Python duck typing, JavaScript without types) appear only when the IDE can resolve them statically.

Other SDK options evaluated:

- **Call Hierarchy** (`LanguageCallHierarchy`, `CallHierarchyProvider`, Java/Kotlin `CallerMethodsTreeStructure`): this is what **Navigate → Call Hierarchy** uses. The tree structures are built by the hierarchy browser (`createHierarchyTreeStructure` is protected), so they are not a convenient API for feeding a custom graph. They also show a tree, not a graph.
- **UAST** (`UMethod`, `UCallExpression.resolve()`): a single implementation could cover Java, Kotlin, Groovy, and Scala. It is a good replacement for the two language extensions if more JVM languages are needed.
- **Kotlin Analysis API** (`analyze { … }`): exact K2 resolution, including implicit invokes and property accessors. Use it if the Kotlin PSI reference approach proves insufficient.
- **Diagrams API** (`com.intellij.diagram`): ships only with IntelliJ IDEA Ultimate. The build now compiles against Ultimate, but using it would make the plugin unusable in Community and the other IDEs.

## LLM chat (llama.cpp)

The chat answers with an LLM when a server is configured in **Settings → Tools → Insight Chat** (or **LLM settings** in the sidebar). Without a server URL it keeps the local demo responses. Start a llama.cpp server with tool calling enabled, then enter its URL, for example `http://127.0.0.1:8080`:

```sh
llama-server -m model.gguf --jinja --port 8080
```

`--jinja` enables the chat template needed for tool calls. Any OpenAI-compatible `/v1/chat/completions` server also works. The model name is optional (llama-server uses its loaded model). An optional API key (`llama-server --api-key`) is stored in the IDE password safe and is sent only over HTTPS or to localhost.

Each question runs a tool loop, like the browser example in `llm_example/`: the model can call read-only project tools, the plugin runs them and sends the results back, and this repeats until the model answers in text. The sidebar shows each tool call as it happens, and **Stop** cancels the turn, including the in-flight HTTP request.

- `list_files`: project content files, optionally under a directory or matching a name glob.
- `read_file`: a numbered line range of a text file (at most 400 lines per call), including unsaved editor changes.
- `search_text`: case-insensitive plain-text search, returning `path:line: text`.

The tools only see project content (sources, tests, resources); excluded folders, paths with `..`, and files outside the project are rejected. Invalid arguments, unknown tools, and tool failures are returned to the model as errors so it can correct itself. After 12 rounds of tool calls, a final request disables new calls (`tool_choice: "none"`) so the model must answer. Each tool result is capped at 16,000 characters. Up to 20 earlier user and assistant messages (24,000 characters) are replayed; tool output from earlier turns is not.

**Privacy:** file contents the model asks for are sent to the configured server. Use a local llama.cpp server to keep source on your machine.

Code layout:

- `llm/LlamaCppClient.kt`: coroutine client for `/v1/chat/completions` with function tools. It accepts a host, a `/v1` base, or a full endpoint URL. It sends `cache_prompt` so llama.cpp reuses the KV cache for the unchanged prompt prefix. It retries 408, 429, and 5xx responses (llama-server returns 503 while loading a model) with exponential backoff, honouring `Retry-After` up to 60 seconds. Other HTTP failures throw `LlmApiException` with the server's response body. Unusable responses throw `LlmProtocolException`. Truncated replies are returned with `FinishReason.TRUNCATED`. Cancellation cancels the HTTP request.
- `llm/LlmModels.kt`, `llm/LlmJson.kt`: provider-neutral messages, tools, and results, and the wire format. Responses tolerate server quirks: tool arguments as JSON strings or objects, `call_id`, `<think>` tags, and `reasoning_content`. Reasoning is replayed on tool-call follow-ups. Usage includes llama.cpp `timings` (cached and evaluated prompt tokens).
- `chat/ToolChat.kt`: `ChatTool`, `ToolChatRunner` (the tool loop), and history compaction. The runner takes any `suspend (CompletionRequest) -> Completion` function, so it can be tested without a server.
- `chat/ProjectTools.kt`: the project tools. Add a tool by implementing `ChatTool` and adding it to `projectTools`.
- `chat/LlmChatBackend.kt`: system prompt, request assembly, and user-facing error messages.
- `chat/ChatSettings.kt`: application settings and the settings page.

```kotlin
val client = LlamaCppClient("http://127.0.0.1:8080")
// From a coroutine owned by the feature's service:
val result = ToolChatRunner(client::complete, projectTools(project))
    .run(listOf(LlmMessage.system("You help with this project."), LlmMessage.user("Where is main defined?")))
println(result.reply)
```

Tests use a local HTTP server and a scripted fake model (`LlamaCppClientTest`, `ToolChatRunnerTest`) and a light project fixture (`ProjectToolsTest`); they need no LLM server.

## Decisions API client

`dev.insight.decisions.DecisionsClient` is a standalone, coroutine-based client for
the [TypeSafe System One HTTP API](https://docs.typesafe.ai/api), referenced by
`jev.md`. Future plugin features can share a client and call `evaluate` with a
`DecisionRequest`. It supports Noul, Choice, and Score questions in one request,
with answers returned under the supplied question IDs and token usage included.

```kotlin
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionsClient

// Obtain apiKey from the feature's credential provider.
val client = DecisionsClient(apiKey)
// Call from a coroutine owned by the feature's service.
val response = client.evaluate(DecisionRequest(
    state = "Help! My payouts have been failing for 3 days.",
    questions = mapOf("urgent" to DecisionQuestion.Noul("Does this convey urgency?")),
))
val urgency = (response.answers.getValue("urgent") as DecisionAnswer.Noul).noul
```

The default model is `jev-latest`; each request can override it. For structured
state, instructions, and criteria, supply Gson `JsonElement` values. Choice
criteria are maps of option names to descriptions (or null), and Score criteria
are ordered lists of level descriptions. Invalid requests fail before network
access; malformed or mismatched responses throw `DecisionProtocolException`.

Calls suspend without blocking the UI thread, and coroutine cancellation cancels
the HTTP request or retry wait. Defaults are a 10-second connection timeout,
60-second timeout per attempt, and at most two retries for HTTP 429/529, with
exponential backoff or `Retry-After` (capped at 60 seconds). Constructor options
allow a different endpoint, request timeout, retry count, or caller-owned HTTP
transport with redirects disabled. Other HTTP failures throw
`DecisionApiException`, exposing the status, response body, and `Retry-After`;
network errors and timeouts propagate as Java IO exceptions. Exception messages
omit the API response body; inspect it explicitly when handling validation errors.

This module does not register a service or store credentials. Features should
provide credentials and own their coroutine lifetimes when integrating it.
Tests use a local HTTP server and require no API key or paid API calls.

## Extend

- `chat/ChatBackend.kt`: backend interface and local demo implementation. `ChatService` picks `LlmChatBackend` or the demo from the settings on each question; backend calls run on IO, with updates on the EDT. Streaming is not implemented yet.
- `chat/ChatService.kt`: per-project conversation shared by sidebar views, including tool-call events and notices (shown, never sent to the model). History is kept in memory for the project session; **Stop** cancels the running turn. Listeners are detached when content is disposed; the platform cancels the service coroutine scope when the project closes.
- `dashboard/DashboardEditor.kt`: custom editor provider and project-scoped virtual file. Opening the dashboard repeatedly reuses the same tab.
- `dashboard/DashboardPanel.kt`: dashboard layout and interactions.
- `dashboard/Charts.kt`: theme-aware Java2D renderers; add new views or replace them with a chart library as the visualization requirements become clearer.
- `dashboard/DemoData.kt`: replace this sample source with an analysis service that collects data in background tasks and publishes immutable snapshots to the UI.
- `llm/`, `chat/ToolChat.kt`, `chat/ProjectTools.kt`: LLM client, tool loop, and project tools (see **LLM chat (llama.cpp)** above).
- `META-INF/plugin.xml`: tool window, settings page, editor provider, and action registration.
- `callgraph/`: call graph action, analysis, layout, and editor tab (see **Call graph** above).

The call graph reads project source locally through the IDE PSI and indexes; no project source is transmitted. The chat sends questions, recent conversation, and the file contents its tools return to the configured LLM server only. This scaffold targets a local IDE; remote development/split mode needs separate design and validation.

JetBrains references: [quick start](https://plugins.jetbrains.com/docs/intellij/plugins-quick-start.html), [tool windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html), [Gradle platform plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), and [Kotlin support](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html).
