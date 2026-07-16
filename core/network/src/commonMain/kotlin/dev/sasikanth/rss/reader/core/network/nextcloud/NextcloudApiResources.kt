package dev.sasikanth.rss.reader.core.network.nextcloud

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

@Serializable
@Resource("/index.php/apps/news/api/v1-2")
class NextcloudNewsApi {

  @Serializable @Resource("feeds") class Feeds(val parent: NextcloudNewsApi = NextcloudNewsApi())

  @Serializable
  @Resource("feeds/{feedId}")
  class Feed(val parent: NextcloudNewsApi = NextcloudNewsApi(), val feedId: Long) {

    @Serializable @Resource("rename") class Rename(val parent: Feed)
  }

  @Serializable
  @Resource("folders")
  class Folders(val parent: NextcloudNewsApi = NextcloudNewsApi())

  @Serializable
  @Resource("folders/{folderId}")
  class Folder(val parent: NextcloudNewsApi = NextcloudNewsApi(), val folderId: Long)

  @Serializable
  @Resource("items")
  class Items(
    val parent: NextcloudNewsApi = NextcloudNewsApi(),
    val batchSize: Int = -1,
    val offset: Int = 0,
    val type: Int = 3,
    val id: Int = 0,
    val getRead: Boolean = true,
  )

  @Serializable
  @Resource("items/updated")
  class UpdatedItems(
    val parent: NextcloudNewsApi = NextcloudNewsApi(),
    val lastModified: Long = 0,
    val type: Int = 3,
    val id: Int = 0,
  )

  @Serializable
  @Resource("items/read/multiple")
  class MarkReadMultiple(val parent: NextcloudNewsApi = NextcloudNewsApi())

  @Serializable
  @Resource("items/unread/multiple")
  class MarkUnreadMultiple(val parent: NextcloudNewsApi = NextcloudNewsApi())

  @Serializable
  @Resource("items/star/multiple")
  class StarMultiple(val parent: NextcloudNewsApi = NextcloudNewsApi())

  @Serializable
  @Resource("items/unstar/multiple")
  class UnstarMultiple(val parent: NextcloudNewsApi = NextcloudNewsApi())

  @Serializable @Resource("user") class User(val parent: NextcloudNewsApi = NextcloudNewsApi())

  @Serializable
  @Resource("version")
  class Version(val parent: NextcloudNewsApi = NextcloudNewsApi())
}
