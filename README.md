# Insight Workbench

A Kotlin plugin for JetBrains IDEs that combines interactive codebase visualization, flow and call analysis, diff review, and LLM-assisted exploration using native IDE and Swing components.

## Overview

- **Application & Call Graphs**: Multi-language static analysis and interactive views for exploring project structure, symbol relationships, call hierarchies, side-by-side source flows, and control-flow charts.
- **Diff Review**: Canvas-based workspace for reviewing working-tree or historical VCS changes with scoped tabs, flow mapping, and source-grounded annotations.
- **Code Review**: Canvas-based workspace for understanding the code in the project or a folder, with chat tools, summaries from the whole scope down to each file and the parts of large files, and topics.
- **Prompt Constructor**: A separate tool window for searching Markdown prompts and appendices, composing a persistent draft, previewing, copying, and saving or exporting it. Open it from Tools → Open Prompt Constructor. Choose a library folder containing `prompts/` and `appendices/` (for the example, choose `pgen-ui/data`). New libraries default to `.insight/prompt-library` in the project; starter templates can be added from the panel.
- **LLM Chat & Tooling**: Configurable local and hosted LLM integration equipped with read-only project tools for codebase Q&A, enrichment, and guided chart navigation.
- **Dashboard & Utilities**: Native editor dashboard views and standalone API clients for structured evaluation.

## Build & Run

Settings → Tools → Insight Chat includes separate maximum concurrent request limits for the LLM and Decisions APIs. Each limit is shared across jobs and projects in the IDE. Excess requests wait without blocking the UI, and applying a new limit also affects existing clients. Individual jobs may use fewer requests than the configured maximum.

Run the plugin in a development IDE instance:

```sh
./gradlew runIde
```

Run tests, package the plugin, and verify its structure:

```sh
./gradlew test buildPlugin verifyPluginStructure
```

For containerized builds and checks without a local toolchain, use the Docker helper (coding agents running in `.code_agents/` containers use `./build.sh` instead; see [AGENTS.md](AGENTS.md)):

```sh
./docker.sh help
./docker.sh check
```

## Entry Points

- [`build.gradle.kts`](build.gradle.kts) — Build configuration, target platform settings, and Gradle tasks.
- [`docker.sh`](docker.sh) — Containerized build, test, and artifact export workflow.
- [`flows/flowchart.md`](flows/flowchart.md) — Design notes for flow charts and interactive chart chat.
- [`flows/code-review.md`](flows/code-review.md) — High-level overview of Code Review.
- [`flows/prompt-constructor.md`](flows/prompt-constructor.md) — High-level overview of Prompt Constructor.
- `META-INF/plugin.xml` (under `src/main/resources/`) — Plugin descriptor registering tool windows, actions, settings, and language extensions.
