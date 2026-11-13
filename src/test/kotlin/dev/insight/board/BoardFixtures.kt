package dev.insight.board

internal fun anchor(first: Int = 1, last: Int = 10, path: String = "src/A.kt", side: BoardSide = BoardSide.AFTER) =
    BoardAnchor(path, first, last, side)

internal fun text(id: String, width: BlockWidth = BlockWidth.NARROW) = TextBlock(id, "Text of $id", width = width)

internal fun boardOf(vararg blocks: Block, edges: List<BoardEdge> = emptyList()) =
    Board("board1", "Sample", blocks.toList(), edges, created = 100, updated = 200)

/** A board with every block kind, a group with children and an edge. */
internal fun fullBoard(): Board = Board(
    id = "board1", title = "How requests flow", created = 1_000, updated = 2_000,
    origin = BoardOrigin("comparison", "main..feature", listOf("src/A.kt", "src/B.kt")),
    blocks = listOf(
        HeadingBlock("h1", "Overview", level = 2),
        TextBlock("t1", "First paragraph.\n\nSecond paragraph.", title = "Intro", tone = Tone.INSIGHT, width = BlockWidth.WIDE),
        CodeBlock("c1", anchor(5, 9), "a\nb\nc\nd\ne", kind = CodeKind.DIFF, title = "Handler",
            annotations = listOf(Annotation(6, "Checks input", Severity.WARNING), Annotation(7, "Fine")), width = BlockWidth.FULL),
        SymbolBlock("s1", 42, "Service.run", meta = "Kotlin · method", summary = "Runs it", anchor = anchor(3, 4, "src/B.kt", BoardSide.BEFORE)),
        ChainBlock("ch1", listOf(ChainStep(1, "main", anchor(1, 2)), ChainStep(2, "run", null, "does work")), title = "Path", complete = false),
        StepsBlock("st1", listOf(Step("Parse"), Step("Run", anchor(8, 8))), title = "Walkthrough", width = BlockWidth.WIDE),
        TableBlock("tb1", listOf("Name", "Where"), listOf(listOf(Cell("a | b"), Cell("here", anchor(1, 1))), listOf(Cell("c"), Cell("d"))),
            title = "Table"),
        TextBlock("t2", "Inside group", width = BlockWidth.WIDE),
        SymbolBlock("s2", 7, "Inner"),
        GroupBlock("g1", listOf("t2", "s2"), title = "Group title", tone = Tone.WARNING),
    ),
    edges = listOf(BoardEdge("t1", "c1", "explains"), BoardEdge("s1", "ch1")),
)
