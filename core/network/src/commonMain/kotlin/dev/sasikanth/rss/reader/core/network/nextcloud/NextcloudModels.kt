package dev.sasikanth.rss.reader.core.network.nextcloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NextcloudFeedsResponse(val feeds: List<NextcloudFeed>, val starredCount: Int = 0)

@Serializable
data class NextcloudFeed(
  val id: Long,
  val title: String,
  val url: String,
  val folderId: Long = 0,
  val unreadCount: Int = 0,
  val ordering: Int = 0,
  val pinned: Boolean = false,
  val faviconLink: String = "",
  val link: String = "",
)

@Serializable data class NextcloudFoldersResponse(val folders: List<NextcloudFolder>)

@Serializable data class NextcloudFolder(val id: Long, val name: String)

@Serializable data class NextcloudItemsResponse(val items: List<NextcloudItem>)

@Serializable
data class NextcloudItem(
  val id: Long,
  val feedId: Long,
  val title: String,
  val url: String,
  val body: String = "",
  val contentHash: String? = null,
  val rtl: Boolean = false,
  val fingerprint: String? = null,
  val unread: Boolean = true,
  val starred: Boolean = false,
  val lastModified: Long = 0,
  val pubDate: Long = 0,
  val mediaThumbnail: String? = null,
  val mediaDescription: String? = null,
  val enclosureMime: String? = null,
  val enclosureLink: String? = null,
  val guid: String = "",
  val guidHash: String = "",
  val author: String? = null,
)

@Serializable data class NextcloudUserResponse(val userId: String, val displayName: String? = null)

@Serializable
data class NextcloudStarItem(
  @SerialName("feedId") val feedId: Long,
  @SerialName("guidHash") val guidHash: String,
)
