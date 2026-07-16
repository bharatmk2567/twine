package dev.sasikanth.rss.reader.data.sync.nextcloud

import dev.sasikanth.rss.reader.core.model.local.ServiceType
import dev.sasikanth.rss.reader.data.repository.RssRepository
import dev.sasikanth.rss.reader.data.repository.SettingsRepository
import dev.sasikanth.rss.reader.data.repository.UserRepository
import dev.sasikanth.rss.reader.data.sync.APIServiceProvider
import dev.sasikanth.rss.reader.di.scopes.AppScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject

@Inject
@AppScope
class NextcloudSyncProvider(
  private val userRepository: UserRepository,
  private val rssRepository: RssRepository,
  private val settingsRepository: SettingsRepository,
) : APIServiceProvider {

  override val cloudService: ServiceType = ServiceType.NEXTCLOUD

  override val isPremium: Boolean = false

  override fun isSignedIn(): Flow<Boolean> {
    return userRepository.user().map {
      it != null && it.serverUrl != null && it.serviceType == ServiceType.NEXTCLOUD
    }
  }

  override suspend fun isSignedInImmediate(): Boolean {
    val user = userRepository.currentUser()
    return user != null && user.serverUrl != null && user.serviceType == ServiceType.NEXTCLOUD
  }

  override suspend fun signOut() {
    userRepository.deleteUser()
    rssRepository.deleteAllLocalData()
  }
}
