package io.github.kmphub.core

/** Renders a [FileChange] as a unified diff for terminal output. */
object UnifiedDiff {

    fun render(change: FileChange, context: Int = 3): String {
        val a = change.before?.split("\n").orEmpty()
        val b = change.after.split("\n")
        val ops = diff(a, b)
        val out = StringBuilder()
        out.append("--- ").append(if (change.before == null) "/dev/null" else "a/${change.path}").append('\n')
        out.append("+++ b/").append(change.path).append('\n')

        val changed = ops.indices.filter { ops[it].first != ' ' }
        if (changed.isEmpty()) return out.toString()
        var start = 0
        while (start < changed.size) {
            var end = start
            while (end + 1 < changed.size && changed[end + 1] - changed[end] <= context * 2) end++
            val from = (changed[start] - context).coerceAtLeast(0)
            val to = (changed[end] + context).coerceAtMost(ops.lastIndex)
            val hunk = ops.subList(from, to + 1)
            val aStart = ops.take(from).count { it.first != '+' } + 1
            val bStart = ops.take(from).count { it.first != '-' } + 1
            out.append("@@ -$aStart,${hunk.count { it.first != '+' }} +$bStart,${hunk.count { it.first != '-' }} @@\n")
            hunk.forEach { (op, line) -> out.append(op).append(line).append('\n') }
            start = end + 1
        }
        return out.toString()
    }

    /** LCS-based line diff; build files are small enough for O(n*m). */
    private fun diff(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val result = mutableListOf<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { result += ' ' to a[i]; i++; j++ }
                j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> { result += '+' to b[j]; j++ }
                else -> { result += '-' to a[i]; i++ }
            }
        }
        return result
    }
}
