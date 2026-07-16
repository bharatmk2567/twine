package dev.sasikanth.rss.reader.core.network.nextcloud

import co.touchlab.kermit.Logger
import dev.sasikanth.rss.reader.core.model.local.User
import dev.sasikanth.rss.reader.util.DispatchersProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.tatarka.inject.annotations.Inject

@Inject
class NextcloudSource(
  private val appHttpClient: HttpClient,
  private val user: suspend () -> User?,
  private val dispatchersProvider: DispatchersProvider,
) {

  private val httpClientMutex = Mutex()
  private val defaultHttpClient by lazy {
    appHttpClient.config {
      followRedirects = true
      install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
  }

  private var _authenticatedHttpClient: HttpClient? = null
  private var cachedUserId: String? = null

  suspend fun verify(endpoint: String, username: String, password: String): Boolean {
    return withContext(dispatchersProvider.io) {
      try {
        val response =
          defaultHttpClient
            .config { defaultRequest { url(endpoint) } }
            .get(NextcloudNewsApi.Version()) {
              header("Authorization", "Basic ${encodeBase64("$username:$password")}")
            }

        response.status.isSuccess()
      } catch (e: Exception) {
        Logger.e(e) { "Nextcloud verification failed" }
        false
      }
    }
  }

  suspend fun feeds(): List<NextcloudFeed> {
    return withContext(dispatchersProvider.io) {
      val response = authenticatedHttpClient().get(NextcloudNewsApi.Feeds())

      if (response.status.isSuccess()) {
        val feedsResponse = response.body<NextcloudFeedsResponse>()
        feedsResponse.feeds
      } else {
        emptyList()
      }
    }
  }

  suspend fun addFeed(url: String, folderId: Long? = null): NextcloudFeed? {
    return withContext(dispatchersProvider.io) {
      val response =
        authenticatedHttpClient().post(NextcloudNewsApi.Feeds()) {
          contentType(ContentType.Application.Json)
          setBody(
            buildJsonObject {
              put("url", url)
              folderId?.let { put("folderId", it) }
            }
          )
        }

      if (response.status.isSuccess()) {
        val feedsResponse = response.body<NextcloudFeedsResponse>()
        feedsResponse.feeds.firstOrNull()
      } else {
        null
      }
    }
  }

  suspend fun deleteFeed(feedId: Long) {
    withContext(dispatchersProvider.io) {
      authenticatedHttpClient().delete(NextcloudNewsApi.Feed(feedId = feedId))
    }
  }

  suspend fun renameFeed(feedId: Long, title: String) {
    withContext(dispatchersProvider.io) {
      authenticatedHttpClient().put(
        NextcloudNewsApi.Feed.Rename(parent = NextcloudNewsApi.Feed(feedId = feedId))
      ) {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("feedTitle", title) })
      }
    }
  }

  suspend fun folders(): List<NextcloudFolder> {
    return withContext(dispatchersProvider.io) {
      val response = authenticatedHttpClient().get(NextcloudNewsApi.Folders())

      if (response.status.isSuccess()) {
        val foldersResponse = response.body<NextcloudFoldersResponse>()
        foldersResponse.folders
      } else {
        emptyList()
      }
    }
  }

  suspend fun addFolder(name: String): NextcloudFolder? {
    return withContext(dispatchersProvider.io) {
      val response =
        authenticatedHttpClient().post(NextcloudNewsApi.Folders()) {
          contentType(ContentType.Application.Json)
          setBody(buildJsonObject { put("name", name) })
        }

      if (response.status.isSuccess()) {
        val foldersResponse = response.body<NextcloudFoldersResponse>()
        foldersResponse.folders.firstOrNull()
      } else {
        null
      }
    }
  }

  suspend fun renameFolder(folderId: Long, name: String) {
    withContext(dispatchersProvider.io) {
      authenticatedHttpClient().put(NextcloudNewsApi.Folder(folderId = folderId)) {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("name", name) })
      }
    }
  }

  suspend fun deleteFolder(folderId: Long) {
    withContext(dispatchersProvider.io) {
      authenticatedHttpClient().delete(NextcloudNewsApi.Folder(folderId = folderId))
    }
  }

  suspend fun items(
    type: Int = 3,
    batchSize: Int = -1,
    offset: Int = 0,
    id: Int = 0,
    getRead: Boolean = true,
  ): List<NextcloudItem> {
    return withContext(dispatchersProvider.io) {
      val response =
        authenticatedHttpClient()
          .get(
            NextcloudNewsApi.Items(
              type = type,
              batchSize = batchSize,
              offset = offset,
              id = id,
              getRead = getRead,
            )
          )

      if (response.status.isSuccess()) {
        val itemsResponse = response.body<NextcloudItemsResponse>()
        itemsResponse.items
      } else {
        emptyList()
      }
    }
  }

  suspend fun updatedItems(lastModified: Long, type: Int = 3, id: Int = 0): List<NextcloudItem> {
    return withContext(dispatchersProvider.io) {
      val response =
        authenticatedHttpClient()
          .get(NextcloudNewsApi.UpdatedItems(lastModified = lastModified, type = type, id = id))

      if (response.status.isSuccess()) {
        val itemsResponse = response.body<NextcloudItemsResponse>()
        itemsResponse.items
      } else {
        emptyList()
      }
    }
  }

  suspend fun markAsRead(itemIds: List<Long>) {
    withContext(dispatchersProvider.io) {
      itemIds.chunked(500).forEach { chunk ->
        authenticatedHttpClient().put(NextcloudNewsApi.MarkReadMultiple()) {
          contentType(ContentType.Application.Json)
          setBody(
            buildJsonObject {
              put("items", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
            }
          )
        }
      }
    }
  }

  suspend fun markAsUnread(itemIds: List<Long>) {
    withContext(dispatchersProvider.io) {
      itemIds.chunked(500).forEach { chunk ->
        authenticatedHttpClient().put(NextcloudNewsApi.MarkUnreadMultiple()) {
          contentType(ContentType.Application.Json)
          setBody(
            buildJsonObject {
              put("items", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
            }
          )
        }
      }
    }
  }

  suspend fun starItems(items: List<NextcloudStarItem>) {
    withContext(dispatchersProvider.io) {
      items.chunked(500).forEach { chunk ->
        authenticatedHttpClient().put(NextcloudNewsApi.StarMultiple()) {
          contentType(ContentType.Application.Json)
          setBody(
            buildJsonObject {
              put(
                "items",
                buildJsonArray {
                  chunk.forEach { item ->
                    add(
                      buildJsonObject {
                        put("feedId", item.feedId)
                        put("guidHash", item.guidHash)
                      }
                    )
                  }
                },
              )
            }
          )
        }
      }
    }
  }

  suspend fun unstarItems(items: List<NextcloudStarItem>) {
    withContext(dispatchersProvider.io) {
      items.chunked(500).forEach { chunk ->
        authenticatedHttpClient().put(NextcloudNewsApi.UnstarMultiple()) {
          contentType(ContentType.Application.Json)
          setBody(
            buildJsonObject {
              put(
                "items",
                buildJsonArray {
                  chunk.forEach { item ->
                    add(
                      buildJsonObject {
                        put("feedId", item.feedId)
                        put("guidHash", item.guidHash)
                      }
                    )
                  }
                },
              )
            }
          )
        }
      }
    }
  }

  suspend fun authenticatedHttpClient(): HttpClient {
    return httpClientMutex.withLock {
      val user = user()
      if (user == null) {
        _authenticatedHttpClient = null
        cachedUserId = null
        return@withLock defaultHttpClient
      }

      if (_authenticatedHttpClient != null && cachedUserId == user.id) {
        return@withLock _authenticatedHttpClient!!
      }

      val token = user.token ?: return@withLock defaultHttpClient
      val serverUrl = user.serverUrl ?: return@withLock defaultHttpClient

      defaultHttpClient
        .config {
          defaultRequest {
            url(serverUrl)
            header("Authorization", "Basic $token")
          }
        }
        .also {
          _authenticatedHttpClient = it
          cachedUserId = user.id
        }
    }
  }

  private fun encodeBase64(input: String): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val bytes = input.encodeToByteArray()
    val sb = StringBuilder()

    var i = 0
    while (i < bytes.size) {
      val b0 = bytes[i].toInt() and 0xFF
      if (i + 2 < bytes.size) {
        val b1 = bytes[i + 1].toInt() and 0xFF
        val b2 = bytes[i + 2].toInt() and 0xFF
        sb.append(chars[(b0 shr 2) and 0x3F])
        sb.append(chars[((b0 shl 4) or (b1 shr 4)) and 0x3F])
        sb.append(chars[((b1 shl 2) or (b2 shr 6)) and 0x3F])
        sb.append(chars[b2 and 0x3F])
      } else if (i + 1 < bytes.size) {
        val b1 = bytes[i + 1].toInt() and 0xFF
        sb.append(chars[(b0 shr 2) and 0x3F])
        sb.append(chars[((b0 shl 4) or (b1 shr 4)) and 0x3F])
        sb.append(chars[(b1 shl 2) and 0x3F])
        sb.append('=')
      } else {
        sb.append(chars[(b0 shr 2) and 0x3F])
        sb.append(chars[(b0 shl 4) and 0x3F])
        sb.append("==")
      }
      i += 3
    }

    return sb.toString()
  }
}
