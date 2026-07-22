package chat.stoat.screens.login

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import chat.stoat.R
import chat.stoat.StoatApplication
import chat.stoat.api.StoatHttp
import chat.stoat.api.StoatJson
import chat.stoat.api.routes.misc.Root
import chat.stoat.composables.generic.FormTextField
import chat.stoat.core.model.data.EndpointConfig
import chat.stoat.persistence.KVStorage
import chat.stoat.selfhost.SelfHostedEndpoints
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.net.URI

/**
 * Fork addition (self-hosted support): lets the user point the app at a
 * self-hosted Stoat/Revolt instance before logging in.
 *
 * The user enters the instance's API URL; the rest of the endpoints (websocket,
 * file server, media proxy, web app) are discovered from the instance's
 * self-describing configuration served at the API root.
 */
class SelfHostedServerScreenViewModel(
    private val kvStorage: KVStorage
) : ViewModel() {
    var url by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var success by mutableStateOf<String?>(null)
        private set

    var currentHost by mutableStateOf(currentApiHost())
        private set

    var isCustom by mutableStateOf(EndpointConfig.isCustom)
        private set

    fun setUrlValue(value: String) {
        url = value
    }

    private fun currentApiHost(): String {
        return try {
            URI(EndpointConfig.apiBase).host ?: EndpointConfig.apiBase
        } catch (e: Exception) {
            EndpointConfig.apiBase
        }
    }

    private fun refreshCurrent() {
        currentHost = currentApiHost()
        isCustom = EndpointConfig.isCustom
    }

    private suspend fun probe(candidate: String): Root? {
        return try {
            val body = StoatHttp.get(candidate).bodyAsText()
            StoatJson.decodeFromString(Root.serializer(), body)
        } catch (e: Exception) {
            Log.d("SelfHostedServer", "No instance configuration at $candidate: ${e.message}")
            null
        }
    }

    fun connect() {
        error = null
        success = null

        var base = url.trim().trimEnd('/')
        if (base.isEmpty()) {
            error = StoatApplication.instance.getString(R.string.self_hosted_error_unreachable)
            return
        }
        if (!base.contains("://")) {
            base = "https://$base"
        }

        busy = true
        viewModelScope.launch {
            try {
                // Try the URL as given, then the conventional /api path for
                // instances hosted under a single domain.
                var apiBase = base
                var root = probe(apiBase)
                if (root == null) {
                    apiBase = "$base/api"
                    root = probe(apiBase)
                }

                if (root == null) {
                    error = StoatApplication.instance
                        .getString(R.string.self_hosted_error_unreachable)
                    return@launch
                }

                SelfHostedEndpoints.save(
                    kvStorage,
                    apiBase = apiBase,
                    websocket = root.ws,
                    files = root.features.autumn.url,
                    proxy = root.features.january.url,
                    webApp = root.app
                )
                refreshCurrent()
                success = StoatApplication.instance
                    .getString(R.string.self_hosted_success, currentHost, root.revolt)
            } catch (e: Exception) {
                Log.e("SelfHostedServer", "Failed to apply instance configuration", e)
                error = e.message ?: "Unknown error"
            } finally {
                busy = false
            }
        }
    }

    fun resetToOfficial() {
        error = null
        success = null
        busy = true
        viewModelScope.launch {
            try {
                SelfHostedEndpoints.clear(kvStorage)
                refreshCurrent()
                success = StoatApplication.instance
                    .getString(R.string.self_hosted_reset_success)
            } catch (e: Exception) {
                Log.e("SelfHostedServer", "Failed to reset instance configuration", e)
                error = e.message ?: "Unknown error"
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun SelfHostedServerScreen(
    navController: NavController,
    viewModel: SelfHostedServerScreenViewModel = koinViewModel()
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .imePadding()
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.self_hosted_heading),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.self_hosted_body),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Normal
                ),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .fillMaxWidth()
            )

            Text(
                text = if (viewModel.isCustom) {
                    stringResource(R.string.self_hosted_current_custom, viewModel.currentHost)
                } else {
                    stringResource(R.string.self_hosted_current_official)
                },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .fillMaxWidth()
            )

            Column(
                modifier = Modifier
                    .width(270.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FormTextField(
                    value = viewModel.url,
                    label = stringResource(R.string.self_hosted_url_label),
                    type = KeyboardType.Uri,
                    action = ImeAction.Done,
                    onChange = viewModel::setUrlValue,
                    enabled = !viewModel.busy,
                    modifier = Modifier
                        .padding(vertical = 25.dp)
                        .testTag("self_hosted_url_field")
                )

                if (viewModel.busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 7.dp))
                }

                if (viewModel.error != null) {
                    Text(
                        text = viewModel.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp
                        ),
                        modifier = Modifier.padding(vertical = 7.dp)
                    )
                }

                if (viewModel.success != null) {
                    Text(
                        text = viewModel.success!!,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp
                        ),
                        modifier = Modifier.padding(vertical = 7.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (viewModel.isCustom) {
                TextButton(
                    onClick = viewModel::resetToOfficial,
                    enabled = !viewModel.busy
                ) {
                    Text(text = stringResource(R.string.self_hosted_reset))
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            Row {
                TextButton(onClick = {
                    navController.popBackStack()
                }) {
                    Text(text = stringResource(R.string.back))
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = viewModel::connect,
                    enabled = !viewModel.busy,
                    modifier = Modifier.testTag("self_hosted_connect_button")
                ) {
                    Text(text = stringResource(R.string.self_hosted_connect))
                }
            }
        }
    }
}
