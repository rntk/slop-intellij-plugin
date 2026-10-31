# API logs flow

API logs capture raw HTTP request and response traffic for debugging interactions with external LLM and Decisions services. Sensitive authentication headers are excluded, and exchanges persist across IDE restarts.

The dedicated tool window presents this traffic in separate views for each service, allowing developers to inspect payloads, monitor request status, and diagnose integration issues.

Entry points for further exploration:

- [ApiTrafficLog.kt](../src/main/kotlin/dev/insight/core/ApiTrafficLog.kt) — request logging and persistence
- [ApiLogToolWindow.kt](../src/main/kotlin/dev/insight/apilog/ApiLogToolWindow.kt) — log inspection panel and tool window
