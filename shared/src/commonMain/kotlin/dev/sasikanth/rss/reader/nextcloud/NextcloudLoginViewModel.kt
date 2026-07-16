package dev.sasikanth.rss.reader.nextcloud

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.sasikanth.rss.reader.core.model.local.ServiceType
import dev.sasikanth.rss.reader.core.network.nextcloud.NextcloudSource
import dev.sasikanth.rss.reader.data.refreshpolicy.RefreshPolicy
import dev.sasikanth.rss.reader.data.repository.RssRepository
import dev.sasikanth.rss.reader.data.repository.SettingsRepository
import dev.sasikanth.rss.reader.data.repository.UserRepository
import dev.sasikanth.rss.reader.data.sync.nextcloud.NextcloudSyncCoordinator
import dev.sasikanth.rss.reader.util.DispatchersProvider
import dev.sasikanth.rss.reader.util.ensureTrailingSlash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

@Stable
@Inject
class NextcloudLoginViewModel(
  private val nextcloudSource: NextcloudSource,
  private val userRepository: UserRepository,
  private val rssRepository: RssRepository,
  private val settingsRepository: SettingsRepository,
  private val syncCoordinator: NextcloudSyncCoordinator,
  private val refreshPolicy: RefreshPolicy,
  private val dispatchersProvider: DispatchersProvider,
) : ViewModel() {

  private val _state = MutableStateFlow(NextcloudLoginState.DEFAULT)
  val state: StateFlow<NextcloudLoginState> = _state.asStateFlow()

  fun onEvent(event: NextcloudLoginEvent) {
    when (event) {
      is NextcloudLoginEvent.OnUrlChanged -> _state.update { it.copy(url = event.url) }
      is NextcloudLoginEvent.OnUsernameChanged ->
        _state.update { it.copy(username = event.username) }
      is NextcloudLoginEvent.OnPasswordChanged ->
        _state.update { it.copy(password = event.password) }
      NextcloudLoginEvent.OnLoginClicked -> login()
      NextcloudLoginEvent.OnConfirmClearDataClicked -> confirmClearData()
      NextcloudLoginEvent.OnConfirmationDismissed ->
        _state.update { it.copy(showConfirmationDialog = false) }
    }
  }

  private fun login() {
    viewModelScope.launch(dispatchersProvider.io) {
      _state.update { it.copy(isLoading = true, error = null) }
      try {
        val endpoint = state.value.url.trim().ensureTrailingSlash()
        val username = state.value.username.trim()
        val password = state.value.password.trim()

        val verified =
          nextcloudSource.verify(endpoint = endpoint, username = username, password = password)

        if (verified) {
          _state.update { it.copy(isLoading = false, showConfirmationDialog = true) }
        } else {
          _state.update { it.copy(isLoading = false, error = NextcloudLoginError.LoginFailed) }
        }
      } catch (e: Exception) {
        _state.update {
          it.copy(isLoading = false, error = NextcloudLoginError.Unknown(e.message ?: ""))
        }
      }
    }
  }

  private fun confirmClearData() {
    viewModelScope.launch(dispatchersProvider.io) {
      _state.update { it.copy(isLoading = true, showConfirmationDialog = false) }
      try {
        Logger.d { "Nextcloud login: starting data clear and user save" }
        userRepository.deleteUser()
        rssRepository.deleteAllLocalData()
        refreshPolicy.clear()

        val endpoint = state.value.url.trim().ensureTrailingSlash()
        val username = state.value.username.trim()
        val password = state.value.password.trim()
        val token = encodeBase64("$username:$password")

        userRepository.saveUser(
          id = username,
          name = username,
          email = "",
          avatarUrl = null,
          token = token,
          refreshToken = "",
          serverUrl = endpoint,
          serviceType = ServiceType.NEXTCLOUD,
        )

        Logger.d { "Nextcloud login: user saved, finishing login" }
        _state.update { it.copy(isLoading = false, loginSuccess = true) }
      } catch (e: Exception) {
        Logger.e(e) { "Nextcloud login: failed to clear data and save user" }
        _state.update {
          it.copy(isLoading = false, error = NextcloudLoginError.Unknown(e.message ?: ""))
        }
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

data class NextcloudLoginState(
  val url: String,
  val username: String,
  val password: String,
  val isLoading: Boolean,
  val loginSuccess: Boolean,
  val showConfirmationDialog: Boolean,
  val error: NextcloudLoginError?,
) {
  companion object {
    val DEFAULT =
      NextcloudLoginState(
        url = "",
        username = "",
        password = "",
        isLoading = false,
        loginSuccess = false,
        showConfirmationDialog = false,
        error = null,
      )
  }
}

sealed interface NextcloudLoginError {
  data object LoginFailed : NextcloudLoginError

  data class Unknown(val message: String) : NextcloudLoginError
}

sealed interface NextcloudLoginEvent {
  data class OnUrlChanged(val url: String) : NextcloudLoginEvent

  data class OnUsernameChanged(val username: String) : NextcloudLoginEvent

  data class OnPasswordChanged(val password: String) : NextcloudLoginEvent

  data object OnLoginClicked : NextcloudLoginEvent

  data object OnConfirmClearDataClicked : NextcloudLoginEvent

  data object OnConfirmationDismissed : NextcloudLoginEvent
}
