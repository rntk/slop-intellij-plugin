package dev.insight.dashboard

data class ModuleMetric(val name: String, val files: Int, val coverage: Int)

object DemoData {
    val modules = listOf(
        ModuleMetric("UI", 42, 84), ModuleMetric("Core", 68, 96),
        ModuleMetric("Analysis", 31, 78), ModuleMetric("Storage", 19, 91)
    )
    val activity = listOf(12, 18, 14, 28, 21, 36, 32, 25, 41, 38, 46, 35, 52, 49)
    fun activityFor(days: Int): List<Int> {
        require(days > 0) { "Days must be positive" }
        return activity.takeLast(days)
    }
}
