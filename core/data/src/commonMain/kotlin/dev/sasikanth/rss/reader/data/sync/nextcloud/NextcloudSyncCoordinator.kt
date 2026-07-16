package dev.sasikanth.rss.reader.data.sync.nextcloud

import co.touchlab.kermit.Logger
import dev.sasikanth.rss.reader.core.base.widget.WidgetUpdater
import dev.sasikanth.rss.reader.core.model.local.Post
import dev.sasikanth.rss.reader.core.model.remote.FeedPayload
import dev.sasikanth.rss.reader.core.model.remote.PostPayload
import dev.sasikanth.rss.reader.core.network.FullArticleFetcher
import dev.sasikanth.rss.reader.core.network.nextcloud.NextcloudItem
import dev.sasikanth.rss.reader.core.network.nextcloud.NextcloudSource
import dev.sasikanth.rss.reader.core.network.parser.common.ArticleHtmlParser
import dev.sasikanth.rss.reader.data.refreshpolicy.RefreshPolicy
import dev.sasikanth.rss.reader.data.repository.RssRepository
import dev.sasikanth.rss.reader.data.repository.SettingsRepository
import dev.sasikanth.rss.reader.data.sync.SyncCoordinator
import dev.sasikanth.rss.reader.data.sync.SyncState
import dev.sasikanth.rss.reader.di.scopes.AppScope
import dev.sasikanth.rss.reader.util.DispatchersProvider
import dev.sasikanth.rss.reader.util.nameBasedUuidOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject

@Inject
@AppScope
class NextcloudSyncCoordinator(
  private val nextcloudSource: NextcloudSource,
  private val rssRepository: RssRepository,
  private val dispatchersProvider: DispatchersProvider,
  private val articleHtmlParser: ArticleHtmlParser,
  private val refreshPolicy: RefreshPolicy,
  private val settingsRepository: SettingsRepository,
  private val fullArticleFetcher: FullArticleFetcher,
  private val widgetUpdater: WidgetUpdater,
) : SyncCoordinator {

  companion object {
    private const val LOCAL_POSTS_PAGE_SIZE = 1000
  }

  private val syncMutex = Mutex()
  private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
  override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

  override suspend fun pull(): Boolean {
    return syncMutex.withLock { pullInternal() }
  }

  override suspend fun pull(feedIds: List<String>): Boolean {
    return withContext(dispatchersProvider.io) {
      syncMutex.withLock {
        feedIds.forEach { feedId -> pullFeedInternal(feedId) }
        true
      }
    }
  }

  override suspend fun pull(feedId: String): Boolean {
    return withContext(dispatchersProvider.io) { syncMutex.withLock { pullFeedInternal(feedId) } }
  }

  override suspend fun push(): Boolean {
    return withContext(dispatchersProvider.io) {
      syncMutex.withLock {
        try {
          pushChanges()
          true
        } catch (e: Exception) {
          Logger.e(e) { "Nextcloud push failed" }
          false
        }
      }
    }
  }

  private suspend fun pullInternal(): Boolean {
    return try {
      val syncStartTime = Clock.System.now()
      val lastSyncedAt = refreshPolicy.fetchLastSyncedAt()
      val isInitialSync = lastSyncedAt == null
      updateSyncState(SyncState.InProgress(0f))

      pushChanges(syncStartTime)

      syncFeeds(syncStartTime)
      updateSyncState(SyncState.InProgress(0.2f))

      syncFolders(syncStartTime)
      updateSyncState(SyncState.InProgress(0.3f))

      if (isInitialSync) {
        syncInitialItems()
      } else {
        val lastModified = lastSyncedAt.minus(24.hours).epochSeconds
        syncUpdatedItems(lastModified)
      }

      refreshPolicy.updateLastSyncedAt()

      syncStatuses(isInitialSync = isInitialSync)

      updateSyncState(SyncState.Complete)
      widgetUpdater.updateUnreadWidget()

      true
    } catch (e: Exception) {
      Logger.e(e) { "Nextcloud pull failed" }
      updateSyncState(SyncState.Error(e))
      false
    }
  }

  private suspend fun pullFeedInternal(feedId: String): Boolean {
    return try {
      updateSyncState(SyncState.InProgress(0f))
      pushChangesForFeed(feedId)

      val feed = rssRepository.feed(feedId)
      if (feed?.remoteId != null) {
        val items =
          nextcloudSource.items(
            type = 0,
            batchSize = -1,
            offset = 0,
            id = feed.remoteId!!.toInt(),
            getRead = true,
          )
        processItems(items)
        updateSyncState(SyncState.Complete)
        widgetUpdater.updateUnreadWidget()
      } else {
        pullInternal()
      }

      true
    } catch (e: Exception) {
      Logger.e(e) { "Nextcloud pull failed for feed: $feedId" }
      updateSyncState(SyncState.Error(e))
      false
    }
  }

  private suspend fun pushChanges(syncStartTime: Instant = Clock.System.now()) {
    pushStatusChanges()
    pushFeedChanges(syncStartTime)
    pushFolderChanges(syncStartTime)
    purgeDeletedSources()
  }

  private suspend fun pushChangesForFeed(feedId: String) {
    pushStatusChanges(feedId)
  }

  private suspend fun purgeDeletedSources() {
    val feedGroups = rssRepository.allFeedGroupsBlocking()
    val feeds = rssRepository.allFeedsBlocking()
    val localSources = feeds + feedGroups

    val toDelete = localSources.filter { it.isDeleted }.toSet()
    if (toDelete.isNotEmpty()) {
      rssRepository.deleteSources(toDelete)
    }
  }

  private suspend fun pushFeedChanges(syncStartTime: Instant) {
    val localFeeds = rssRepository.allFeedsBlocking()
    val lastSyncedAt = refreshPolicy.fetchLastSyncedAt() ?: Instant.DISTANT_PAST

    val hasUpdatedFeeds =
      localFeeds.any { (it.lastUpdatedAt ?: Instant.DISTANT_PAST) > lastSyncedAt }
    if (!hasUpdatedFeeds) return

    localFeeds
      .filter {
        it.isDeleted &&
          it.remoteId != null &&
          (it.lastUpdatedAt ?: Instant.DISTANT_PAST) > lastSyncedAt
      }
      .forEach { feed -> nextcloudSource.deleteFeed(feed.remoteId!!.toLong()) }

    localFeeds
      .filter {
        !it.isDeleted &&
          it.remoteId == null &&
          (it.lastUpdatedAt ?: Instant.DISTANT_PAST) > lastSyncedAt
      }
      .forEach { feed ->
        val response = nextcloudSource.addFeed(feed.link)
        if (response != null) {
          rssRepository.updateFeedRemoteId(response.id.toString(), feed.id, syncStartTime)
        }
      }

    localFeeds
      .filter {
        !it.isDeleted &&
          it.remoteId != null &&
          (it.lastUpdatedAt ?: Instant.DISTANT_PAST) > lastSyncedAt
      }
      .forEach { feed ->
        nextcloudSource.renameFeed(feed.remoteId!!.toLong(), feed.name)
        rssRepository.updateFeedLastUpdatedAt(feed.id, syncStartTime)
      }
  }

  private suspend fun pushFolderChanges(syncStartTime: Instant) {
    val localGroups = rssRepository.allFeedGroupsBlocking()
    val lastSyncedAt = refreshPolicy.fetchLastSyncedAt() ?: Instant.DISTANT_PAST

    val hasUpdatedGroups = localGroups.any { it.updatedAt > lastSyncedAt }
    if (!hasUpdatedGroups) return

    val remoteFolders = nextcloudSource.folders()
    val remoteFolderIds = remoteFolders.map { it.id }.toSet()

    localGroups
      .filter { it.isDeleted && it.updatedAt > lastSyncedAt }
      .forEach { group ->
        if (group.remoteId != null) {
          nextcloudSource.deleteFolder(group.remoteId!!.toLong())
        }
      }

    localGroups
      .filter { !it.isDeleted && it.remoteId == null && it.updatedAt > lastSyncedAt }
      .forEach { group ->
        val response = nextcloudSource.addFolder(group.name)
        if (response != null) {
          rssRepository.updateFeedGroupRemoteId(response.id.toString(), group.id, syncStartTime)
        }
      }

    localGroups
      .filter {
        !it.isDeleted &&
          it.remoteId != null &&
          it.updatedAt > lastSyncedAt &&
          it.remoteId!!.toLongOrNull() in remoteFolderIds
      }
      .forEach { group ->
        nextcloudSource.renameFolder(group.remoteId!!.toLong(), group.name)
        rssRepository.updateFeedGroupUpdatedAt(group.id, syncStartTime)
      }
  }

  private suspend fun syncFeeds(syncStartTime: Instant) {
    val remoteFeeds = nextcloudSource.feeds()
    val remoteFolders = nextcloudSource.folders()
    val localFeeds = rssRepository.allFeedsBlocking()
    val localGroups = rssRepository.allFeedGroupsBlocking()

    val remoteIds = remoteFeeds.map { it.id.toString() }.toSet()
    val remoteUrls = remoteFeeds.map { it.url }.toSet()

    localFeeds.forEach { localFeed ->
      if (
        !localFeed.isDeleted &&
          localFeed.remoteId != null &&
          localFeed.remoteId !in remoteIds &&
          localFeed.link !in remoteUrls
      ) {
        rssRepository.removeFeed(localFeed.id)
      }
    }

    val remoteFolderIds = remoteFolders.map { it.id }.toSet()
    localGroups.forEach { localGroup ->
      if (
        !localGroup.isDeleted &&
          localGroup.remoteId != null &&
          localGroup.remoteId!!.toLongOrNull() !in remoteFolderIds
      ) {
        rssRepository.markSourcesAsDeleted(setOf(localGroup))
      }
    }

    val localFeedsByLink = localFeeds.associateBy { it.link }
    val localFeedsByRemoteId =
      localFeeds.filter { it.remoteId != null }.associateBy { it.remoteId!! }

    remoteFeeds.forEach { remoteFeed ->
      val localFeed =
        localFeedsByRemoteId[remoteFeed.id.toString()] ?: localFeedsByLink[remoteFeed.url]

      if (localFeed != null) {
        if (
          localFeed.remoteId != remoteFeed.id.toString() ||
            localFeed.name != remoteFeed.title ||
            localFeed.homepageLink != remoteFeed.link
        ) {
          rssRepository.upsertFeeds(
            listOf(
              localFeed.copy(
                name = remoteFeed.title,
                homepageLink = remoteFeed.link,
                remoteId = remoteFeed.id.toString(),
                lastUpdatedAt = syncStartTime,
                isDeleted = false,
              )
            )
          )
        }
      } else {
        rssRepository
          .upsertFeedWithPosts(
            feedPayload =
              FeedPayload(
                name = remoteFeed.title,
                icon = remoteFeed.faviconLink,
                description = "",
                homepageLink = remoteFeed.link,
                link = remoteFeed.url,
                posts = emptyFlow(),
              ),
            updateFeed = true,
          )
          .also { rssRepository.updateFeedRemoteId(remoteFeed.id.toString(), it, syncStartTime) }
      }
    }

    purgeDeletedSources()
  }

  private suspend fun syncFolders(syncStartTime: Instant) {
    val remoteFolders = nextcloudSource.folders()
    val localGroups = rssRepository.allFeedGroupsBlocking()

    val localGroupsByRemoteId =
      localGroups.filter { it.remoteId != null }.associateBy { it.remoteId!! }
    val localGroupsByName = localGroups.associateBy { it.name }

    remoteFolders.forEach { folder ->
      val localGroup = localGroupsByRemoteId[folder.id.toString()] ?: localGroupsByName[folder.name]
      if (localGroup != null) {
        if (localGroup.remoteId != folder.id.toString() || localGroup.name != folder.name) {
          rssRepository.upsertGroup(
            id = localGroup.id,
            name = folder.name,
            pinnedAt = localGroup.pinnedAt,
            updatedAt = syncStartTime,
            isDeleted = false,
            remoteId = folder.id.toString(),
          )
        }
      } else {
        rssRepository.upsertGroup(
          id = nameBasedUuidOf(folder.name).toString(),
          name = folder.name,
          pinnedAt = null,
          updatedAt = syncStartTime,
          isDeleted = false,
          remoteId = folder.id.toString(),
        )
      }
    }
  }

  private suspend fun syncInitialItems() {
    val downloadFullContent = settingsRepository.downloadFullContent.first()

    val unreadItems = nextcloudSource.items(type = 3, batchSize = -1, offset = 0, getRead = false)
    processItems(unreadItems, downloadFullContent)
    updateSyncState(SyncState.InProgress(0.6f))

    val starredItems = nextcloudSource.items(type = 2, batchSize = -1, offset = 0, getRead = true)
    processItems(starredItems, downloadFullContent)
    updateSyncState(SyncState.InProgress(0.8f))
  }

  private suspend fun syncUpdatedItems(lastModified: Long) {
    val downloadFullContent = settingsRepository.downloadFullContent.first()

    val items = nextcloudSource.updatedItems(lastModified = lastModified, type = 3, id = 0)
    processItems(items, downloadFullContent)
    updateSyncState(SyncState.InProgress(0.7f))

    val starredItems = nextcloudSource.updatedItems(lastModified = lastModified, type = 2, id = 0)
    processItems(starredItems, downloadFullContent)
    updateSyncState(SyncState.InProgress(0.8f))
  }

  private suspend fun processItems(
    items: List<NextcloudItem>,
    downloadFullContent: Boolean = false,
  ) {
    if (items.isEmpty()) return

    val remoteIds = items.map { it.id.toString() }.toSet()
    val urls = items.map { it.url }.toSet()
    val feedRemoteIds = items.map { it.feedId.toString() }.toSet()

    val existingPostsByRemoteId =
      rssRepository.postsByRemoteIds(remoteIds).associateBy { it.remoteId }
    val existingPostsByLink = rssRepository.postsByLinks(urls).associateBy { it.link }
    val existingFeeds = rssRepository.feedsByRemoteIds(feedRemoteIds).associateBy { it.remoteId }

    val postsToUpsertByFeed = mutableMapOf<String, MutableList<PostPayload>>()

    items.forEach { item ->
      val remoteId = item.id.toString()
      val localPost = existingPostsByRemoteId[remoteId] ?: existingPostsByLink[item.url]

      if (localPost != null) {
        if (localPost.remoteId != remoteId) {
          rssRepository.updatePostRemoteId(remoteId, localPost.id)
        }
      } else {
        val feed = existingFeeds[item.feedId.toString()]
        if (feed != null) {
          val htmlContent = articleHtmlParser.parse(item.body)
          val fullContent =
            if (downloadFullContent && item.url.isNotBlank()) {
              fullArticleFetcher.fetch(item.url, remoteId).getOrNull()
            } else {
              null
            }
          val postPubDate = item.pubDate * 1000
          val audioUrl =
            item.enclosureLink?.takeIf { item.enclosureMime?.startsWith("audio/") == true }
              ?: htmlContent?.audioUrl
          val postPayload =
            PostPayload(
              title = item.title,
              link = item.url,
              description = htmlContent?.textContent ?: "",
              rawContent = htmlContent?.cleanedHtml ?: item.body,
              imageUrl = htmlContent?.heroImage ?: item.mediaThumbnail,
              audioUrl = audioUrl,
              date = postPubDate,
              commentsLink = null,
              fullContent = fullContent,
              isDateParsedCorrectly = true,
              remoteId = remoteId,
            )

          postsToUpsertByFeed.getOrPut(feed.id) { mutableListOf() }.add(postPayload)
        }
      }
    }

    postsToUpsertByFeed.forEach { (feedId, posts) ->
      val feed = existingFeeds.values.find { it.id == feedId }!!
      rssRepository.upsertFeedWithPosts(
        feedPayload =
          FeedPayload(
            name = feed.name,
            icon = feed.icon,
            description = feed.description,
            homepageLink = feed.homepageLink,
            link = feed.link,
            posts = flowOf(*posts.toTypedArray()),
          ),
        feedId = feed.id,
        updateFeed = false,
      )
    }
  }

  private suspend fun syncStatuses(isInitialSync: Boolean = false) {
    val unreadItems = nextcloudSource.items(type = 3, batchSize = -1, offset = 0, getRead = false)
    val unreadIds = unreadItems.map { it.id.toString() }.toSet()

    val starredItems = nextcloudSource.items(type = 2, batchSize = -1, offset = 0, getRead = true)
    val bookmarkIds = starredItems.map { it.id.toString() }.toSet()

    var localOffset = 0L
    var localPosts: List<Post> = emptyList()
    do {
      localPosts =
        rssRepository.postsWithRemoteIdPaged(
          limit = LOCAL_POSTS_PAGE_SIZE.toLong(),
          offset = localOffset,
        )

      val toMarkRead = mutableSetOf<String>()
      val toMarkUnread = mutableSetOf<String>()
      val toBookmark = mutableSetOf<String>()
      val toUnbookmark = mutableSetOf<String>()
      val toUpdateSyncedAt = mutableSetOf<String>()

      localPosts.forEach { post ->
        val remoteRead = post.remoteId !in unreadIds
        val remoteBookmarked = post.remoteId in bookmarkIds

        if (post.syncedAt >= post.updatedAt) {
          var changed = false
          if (post.read != remoteRead) {
            if (remoteRead) toMarkRead.add(post.id) else toMarkUnread.add(post.id)
            changed = true
          }
          if (post.bookmarked != remoteBookmarked) {
            if (remoteBookmarked) toBookmark.add(post.id) else toUnbookmark.add(post.id)
            changed = true
          }

          if (changed) {
            toUpdateSyncedAt.add(post.id)
          }
        }
      }

      if (toMarkRead.isNotEmpty()) {
        rssRepository.updatePostReadStatus(toMarkRead, read = true, recordHistory = !isInitialSync)
      }
      if (toMarkUnread.isNotEmpty()) {
        rssRepository.updatePostReadStatus(
          toMarkUnread,
          read = false,
          recordHistory = !isInitialSync,
        )
      }
      if (toBookmark.isNotEmpty()) rssRepository.updateBookmarkStatus(toBookmark, bookmarked = true)
      if (toUnbookmark.isNotEmpty())
        rssRepository.updateBookmarkStatus(toUnbookmark, bookmarked = false)
      if (toUpdateSyncedAt.isNotEmpty())
        rssRepository.updatePostSyncedAt(toUpdateSyncedAt, Clock.System.now())

      localOffset += localPosts.size
    } while (localPosts.size >= LOCAL_POSTS_PAGE_SIZE)
  }

  private suspend fun pushStatusChanges(feedId: String? = null) {
    while (true) {
      val dirtyPosts =
        if (feedId != null) {
          rssRepository.postsWithLocalChangesForFeedPaged(
            feedId = feedId,
            limit = LOCAL_POSTS_PAGE_SIZE.toLong(),
            offset = 0,
          )
        } else {
          rssRepository.postsWithLocalChangesPaged(
            limit = LOCAL_POSTS_PAGE_SIZE.toLong(),
            offset = 0,
          )
        }

      if (dirtyPosts.isEmpty()) return

      val toMarkRead = mutableListOf<Long>()
      val toMarkUnread = mutableListOf<Long>()

      dirtyPosts.forEach { post ->
        val remoteId = post.remoteId?.toLongOrNull() ?: return@forEach

        if (post.read) {
          toMarkRead.add(remoteId)
        } else {
          toMarkUnread.add(remoteId)
        }
      }

      if (toMarkRead.isNotEmpty()) nextcloudSource.markAsRead(toMarkRead)
      if (toMarkUnread.isNotEmpty()) nextcloudSource.markAsUnread(toMarkUnread)

      rssRepository.updatePostSyncedAt(dirtyPosts)
    }
  }

  private fun updateSyncState(newState: SyncState) {
    _syncState.value = newState
  }
}
