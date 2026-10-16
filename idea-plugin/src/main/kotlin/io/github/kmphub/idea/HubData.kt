package io.github.kmphub.idea

import com.intellij.openapi.application.PathManager
import com.intellij.util.io.HttpRequests
import io.github.kmphub.core.Http
import io.github.kmphub.core.LibraryCatalog
import io.github.kmphub.core.LiveCatalog
import io.github.kmphub.core.LiveSnapshot
import io.github.kmphub.core.MavenIndex
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Goes through the IDE's HTTP stack, so proxy and certificate settings apply. */
object IdeHttp : Http {
    override fun get(url: String): String? = try {
        HttpRequests.request(url)
            .productNameAsUserAgent()
            .connectTimeout(10_000)
            .readTimeout(30_000)
            .readString()
    } catch (e: IOException) {
        null // includes HttpStatusException for 404s
    }

    override fun postJson(url: String, body: String): String? = try {
        HttpRequests.post(url, "application/json")
            .productNameAsUserAgent()
            .connectTimeout(10_000)
            .readTimeout(30_000)
            .connect { request ->
                request.write(body)
                request.readString()
            }
    } catch (e: IOException) {
        null
    }
}

/** App-wide live data shared by every open project. Call [refresh] off the EDT. */
object HubData {
    val index = MavenIndex(IdeHttp)

    private val live = LiveCatalog(
        index = index,
        cacheFile = File(PathManager.getSystemPath(), "kmphub/versions.json"),
    )

    @Volatile
    var snapshot: LiveSnapshot = LiveSnapshot(LibraryCatalog.bundled(), 0)
        private set

    fun refresh(force: Boolean = false): LiveSnapshot = live.load(forceRefresh = force).also { snapshot = it }

    fun describe(snapshot: LiveSnapshot = this.snapshot): String {
        if (snapshot.fetchedAt == 0L) return "Versions: bundled (Maven not reached yet)"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - snapshot.fetchedAt)
        val age = when {
            minutes < 1 -> "just now"
            minutes < 120 -> "$minutes min ago"
            else -> "${minutes / 60} h ago"
        }
        return "Versions: latest from Maven, checked $age"
    }
}
