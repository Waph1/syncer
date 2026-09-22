package io.github.waph1.syncer.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TodoTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    val completed: Boolean = false,
    val completedDate: LocalDate? = null,
    val due: LocalDate? = null,
    val parentId: String? = null,
    /** Google Tasks ordering key (lexicographic). */
    val position: String = "",
)

/**
 * Writes a task list in todo.txt format (https://github.com/todotxt/todo.txt):
 * `x <completion date> <description> due:YYYY-MM-DD`. Notes are appended to the
 * description on a single line; subtasks follow their parent task.
 */
object TodoTxtWriter {
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun write(tasks: List<TodoTask>): String {
        val ids = tasks.map { it.id }.toSet()
        val children = tasks.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId!! }
        val roots = tasks.filter { it.parentId == null || it.parentId !in ids }
        val order = compareBy<TodoTask>({ it.position }, { it.title.lowercase(Locale.ROOT) }, { it.id })
        val open = roots.filter { !it.completed }.sortedWith(order)
        val done = roots.filter { it.completed }
            .sortedWith(compareByDescending<TodoTask> { it.completedDate ?: LocalDate.MIN }.then(order))

        val sb = StringBuilder()
        for (task in open + done) {
            sb.append(line(task)).append('\n')
            children[task.id].orEmpty().sortedWith(order).forEach { sb.append(line(it)).append('\n') }
        }
        return sb.toString()
    }

    fun line(task: TodoTask): String = buildString {
        if (task.completed) {
            append("x ")
            task.completedDate?.let { append(ISO.format(it)).append(' ') }
        }
        append(singleLine(task.title).ifEmpty { "(senza titolo)" })
        task.notes?.let(::singleLine)?.takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
        task.due?.let { append(" due:").append(ISO.format(it)) }
    }

    private fun singleLine(text: String): String =
        text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
}
