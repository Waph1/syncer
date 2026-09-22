package io.github.waph1.syncer.source

import io.github.waph1.syncer.format.TodoTask
import java.time.ZoneId

/** Reads all task lists of the account through the Google Tasks API v1. */
class TasksSource(private val client: GoogleApiClient, private val zone: ZoneId = ZoneId.systemDefault()) {

    data class TaskList(val id: String, val title: String, val tasks: List<TodoTask>)

    fun read(): List<TaskList> {
        val lists = mutableListOf<ApiTaskList>()
        var page: String? = null
        do {
            val url = "$BASE/users/@me/lists?maxResults=100" + (page?.let { "&pageToken=" + GoogleApiClient.encode(it) } ?: "")
            val response = client.get(url, TaskListsResponse.serializer())
            lists += response.items
            page = response.nextPageToken
        } while (page != null)

        return lists.map { list ->
            val tasks = mutableListOf<ApiTask>()
            var taskPage: String? = null
            do {
                // showHidden is needed for tasks completed in Google's own apps.
                val url = "$BASE/lists/${GoogleApiClient.encode(list.id)}/tasks" +
                    "?maxResults=100&showCompleted=true&showHidden=true&showDeleted=false" +
                    (taskPage?.let { "&pageToken=" + GoogleApiClient.encode(it) } ?: "")
                val response = client.get(url, TasksResponse.serializer())
                tasks += response.items
                taskPage = response.nextPageToken
            } while (taskPage != null)
            TaskList(list.id, list.title.ifBlank { "Elenco" }, tasks.filter { !it.deleted }.map { it.toTodo(zone) })
        }
    }

    private companion object {
        const val BASE = "https://tasks.googleapis.com/tasks/v1"
    }
}
