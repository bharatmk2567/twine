package dev.sasikanth.rss.reader.nextcloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sasikanth.rss.reader.components.AlertDialog
import dev.sasikanth.rss.reader.components.Button
import dev.sasikanth.rss.reader.components.SimpleTopAppBar
import dev.sasikanth.rss.reader.components.TextField
import dev.sasikanth.rss.reader.nextcloud.NextcloudLoginError
import dev.sasikanth.rss.reader.nextcloud.NextcloudLoginEvent
import dev.sasikanth.rss.reader.nextcloud.NextcloudLoginState
import dev.sasikanth.rss.reader.nextcloud.NextcloudLoginViewModel
import dev.sasikanth.rss.reader.ui.AppTheme
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import twine.shared.generated.resources.Res
import twine.shared.generated.resources.buttonCancel
import twine.shared.generated.resources.nextcloudClearDataDesc
import twine.shared.generated.resources.nextcloudClearDataPositive
import twine.shared.generated.resources.nextcloudClearDataTitle
import twine.shared.generated.resources.nextcloudErrorLoginFailed
import twine.shared.generated.resources.nextcloudErrorUnknown
import twine.shared.generated.resources.nextcloudLoginButton
import twine.shared.generated.resources.nextcloudLoginTitle
import twine.shared.generated.resources.nextcloudPassword
import twine.shared.generated.resources.nextcloudServerUrl
import twine.shared.generated.resources.nextcloudUsername

const val NEXTCLOUD_LOGIN_SUCCESS_KEY = "dev.sasikanth.twine.NEXTCLOUD_LOGIN_SUCCESS"

@Composable
fun NextcloudLoginScreen(
  viewModel: NextcloudLoginViewModel,
  onLoginSuccess: () -> Unit,
  goBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  LaunchedEffect(state.loginSuccess, state.error) {
    if (state.loginSuccess) {
      onLoginSuccess()
    }

    if (state.error != null) {
      val errorMessage =
        when (val error = state.error!!) {
          NextcloudLoginError.LoginFailed -> getString(Res.string.nextcloudErrorLoginFailed)
          is NextcloudLoginError.Unknown ->
            error.message.ifBlank { getString(Res.string.nextcloudErrorUnknown) }
        }
    }
  }

  NextcloudLoginContent(
    state = state,
    onEvent = viewModel::onEvent,
    goBack = goBack,
    modifier = modifier,
  )
}

@Composable
private fun NextcloudLoginContent(
  state: NextcloudLoginState,
  onEvent: (NextcloudLoginEvent) -> Unit,
  goBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val (urlFocus, usernameFocus, passwordFocus) = remember { FocusRequester.createRefs() }

  LaunchedEffect(state.error) {
    val error = state.error
    if (error != null) {
      val errorMessage =
        when (error) {
          NextcloudLoginError.LoginFailed -> getString(Res.string.nextcloudErrorLoginFailed)
          is NextcloudLoginError.Unknown ->
            error.message.ifBlank { getString(Res.string.nextcloudErrorUnknown) }
        }
      snackbarHostState.showSnackbar(message = errorMessage)
    }
  }

  if (state.showConfirmationDialog) {
    AlertDialog(
      title = stringResource(Res.string.nextcloudClearDataTitle),
      text = stringResource(Res.string.nextcloudClearDataDesc),
      confirmText = stringResource(Res.string.nextcloudClearDataPositive),
      dismissText = stringResource(Res.string.buttonCancel),
      onConfirm = { onEvent(NextcloudLoginEvent.OnConfirmClearDataClicked) },
      onDismiss = { onEvent(NextcloudLoginEvent.OnConfirmationDismissed) },
    )
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      SimpleTopAppBar(title = stringResource(Res.string.nextcloudLoginTitle), onBackClick = goBack)
    },
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { snackbarData ->
        Snackbar(
          modifier = Modifier.padding(12.dp),
          content = {
            Text(
              text = snackbarData.visuals.message,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
            )
          },
          containerColor = AppTheme.colorScheme.inverseSurface,
          contentColor = AppTheme.colorScheme.inverseOnSurface,
        )
      }
    },
    bottomBar = {
      Button(
        modifier =
          Modifier.background(AppTheme.colorScheme.backdrop)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .imePadding()
            .fillMaxWidth()
            .requiredHeight(56.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = AppTheme.colorScheme.inverseSurface,
            contentColor = AppTheme.colorScheme.inverseOnSurface,
          ),
        enabled =
          !state.isLoading &&
            state.url.isNotBlank() &&
            state.username.isNotBlank() &&
            state.password.isNotBlank(),
        shape = MaterialTheme.shapes.extraLarge,
        onClick = { onEvent(NextcloudLoginEvent.OnLoginClicked) },
      ) {
        if (state.isLoading) {
          CircularProgressIndicator(
            color = AppTheme.colorScheme.primary,
            modifier = Modifier.requiredSize(24.dp),
            strokeWidth = 2.dp,
          )
        } else {
          Text(
            text = stringResource(Res.string.nextcloudLoginButton).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
          )
        }
      }
    },
    containerColor = AppTheme.colorScheme.backdrop,
    contentColor = Color.Unspecified,
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item { Spacer(Modifier.requiredHeight(8.dp)) }

      item {
        TextField(
          value = state.url,
          onValueChange = { onEvent(NextcloudLoginEvent.OnUrlChanged(it)) },
          hint = stringResource(Res.string.nextcloudServerUrl),
          modifier =
            Modifier.fillMaxWidth().focusRequester(urlFocus).focusProperties {
              next = usernameFocus
            },
          enabled = !state.isLoading,
          keyboardOptions =
            KeyboardOptions(
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Uri,
              imeAction = ImeAction.Next,
            ),
        )
      }

      item {
        TextField(
          value = state.username,
          onValueChange = { onEvent(NextcloudLoginEvent.OnUsernameChanged(it)) },
          hint = stringResource(Res.string.nextcloudUsername),
          modifier =
            Modifier.fillMaxWidth().focusRequester(usernameFocus).focusProperties {
              previous = urlFocus
              next = passwordFocus
            },
          enabled = !state.isLoading,
          keyboardOptions =
            KeyboardOptions(
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Email,
              imeAction = ImeAction.Next,
            ),
        )
      }

      item {
        TextField(
          value = state.password,
          onValueChange = { onEvent(NextcloudLoginEvent.OnPasswordChanged(it)) },
          hint = stringResource(Res.string.nextcloudPassword),
          modifier =
            Modifier.fillMaxWidth().focusRequester(passwordFocus).focusProperties {
              previous = usernameFocus
            },
          enabled = !state.isLoading,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions =
            KeyboardOptions(
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Done,
            ),
          keyboardActions =
            KeyboardActions(onDone = { onEvent(NextcloudLoginEvent.OnLoginClicked) }),
        )
      }
    }
  }
}
