package io.github.waph1.syncer.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TodoTxtWriterTest {

    @Test
    fun formatsOpenAndCompletedTasks() {
        assertEquals(
            "Comprare il latte due:2024-01-05",
            TodoTxtWriter.line(TodoTask("1", "Comprare il latte", due = LocalDate.of(2024, 1, 5))),
        )
        assertEquals(
            "x 2024-01-02 Pagare bolletta — entro venerdì / conto cointestato",
            TodoTxtWriter.line(
                TodoTask("2", "Pagare bolletta", notes = "entro venerdì\n\nconto cointestato\n", completed = true, completedDate = LocalDate.of(2024, 1, 2)),
            ),
        )
        assertEquals("x Senza data", TodoTxtWriter.line(TodoTask("3", "Senza data", completed = true)))
        assertEquals("(senza titolo)", TodoTxtWriter.line(TodoTask("4", "  ")))
        assertEquals("Titolo su due righe", TodoTxtWriter.line(TodoTask("5", "Titolo su\ndue righe")).replace(" / ", " "))
    }

    @Test
    fun ordersOpenTasksByPositionThenCompletedAndKeepsSubtasksUnderParent() {
        val tasks = listOf(
            TodoTask("done-old", "Vecchia", completed = true, completedDate = LocalDate.of(2023, 1, 1), position = "0001"),
            TodoTask("b", "Secondo", position = "0002"),
            TodoTask("sub", "Sotto-attività", parentId = "a", position = "0001"),
            TodoTask("a", "Primo", position = "0001"),
            TodoTask("done-new", "Recente", completed = true, completedDate = LocalDate.of(2024, 1, 1), position = "0003"),
            TodoTask("orphan", "Orfana", parentId = "missing", position = "0003"),
        )
        assertEquals(
            listOf(
                "Primo",
                "Sotto-attività",
                "Secondo",
                "Orfana",
                "x 2024-01-01 Recente",
                "x 2023-01-01 Vecchia",
            ).joinToString("\n", postfix = "\n"),
            TodoTxtWriter.write(tasks),
        )
    }

    @Test
    fun emptyListProducesEmptyFile() {
        assertEquals("", TodoTxtWriter.write(emptyList()))
    }
}
