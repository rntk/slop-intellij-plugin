# Insight Workbench

A Kotlin plugin for JetBrains IDEs that combines interactive codebase visualization, flow and call analysis, diff review, and LLM-assisted exploration using native IDE and Swing components.

## Overview

- **Application & Call Graphs**: Multi-language static analysis and interactive views for exploring project structure, symbol relationships, call hierarchies, side-by-side source flows, and control-flow charts.
- **Diff Review**: Canvas-based workspace for reviewing working-tree or historical VCS changes with scoped tabs, flow mapping, and source-grounded annotations.
- **LLM Chat & Tooling**: Configurable local and hosted LLM integration equipped with read-only project tools for codebase Q&A, enrichment, and guided chart navigation.
- **Dashboard & Utilities**: Native editor dashboard views and standalone API clients for structured evaluation.

## Build & Run

Run the plugin in a development IDE instance:

```sh
./gradlew runIde
```

Run tests, package the plugin, and verify its structure:

```sh
./gradlew test buildPlugin verifyPluginStructure
```

For containerized builds and checks without a local toolchain, use the Docker helper:

```sh
./docker.sh help
./docker.sh check
```

## Entry Points

- [`build.gradle.kts`](build.gradle.kts) — Build configuration, target platform settings, and Gradle tasks.
- [`docker.sh`](docker.sh) — Containerized build, test, and artifact export workflow.
- [`flows/flowchart.md`](flows/flowchart.md) — Design notes for flow charts and interactive chart chat.
- `META-INF/plugin.xml` (under `src/main/resources/`) — Plugin descriptor registering tool windows, actions, settings, and language extensions.
