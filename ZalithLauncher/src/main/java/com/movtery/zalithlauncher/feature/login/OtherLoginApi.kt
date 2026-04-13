package com.movtery.zalithlauncher.feature.login

import android.content.Context
import com.google.gson.Gson
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.path.UrlManager
import com.movtery.zalithlauncher.utils.path.UrlManager.Companion.createRequestBuilder
import com.movtery.zalithlauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.value.MinecraftAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

object OtherLoginApi {// Handles adding Ely.by accounts DNA Mobile
    private const val TAG = "OtherLoginApi"
    private const val ELY_BY_AUTH_BASE = "https://authserver.ely.by"

    private var client: OkHttpClient = UrlManager.createOkHttpClient()
    private var baseUrl: String? = null

    fun setBaseUrl(baseUrl: String) {
        this.baseUrl = normalizeBaseUrl(baseUrl)
        Logging.i(TAG, "Using login base URL: ${this.baseUrl}")
    }

    @Throws(IOException::class)
    fun login(context: Context, userName: String?, password: String?, listener: Listener) {
        val normalizedBaseUrl = baseUrl
        if (normalizedBaseUrl.isNullOrBlank()) {
            listener.onFailed(context.getString(R.string.other_login_baseurl_not_set))
            return
        }

        val agent = AuthRequest.Agent().apply {
            name = "Minecraft"
            version = 1
        }

        val authRequest = AuthRequest().apply {
            username = userName
            this.password = password
            this.agent = agent
            requestUser = true
            clientToken = UUID.randomUUID().toString().lowercase(Locale.ROOT)
        }

        val data = Gson().toJson(authRequest)
        callLogin(data, "/authserver/authenticate", listener)
    }

    @Throws(IOException::class)
    fun refresh(
        context: Context,
        account: MinecraftAccount,
        select: Boolean,
        listener: Listener
    ) {
        val normalizedBaseUrl = baseUrl
        if (normalizedBaseUrl.isNullOrBlank()) {
            listener.onFailed(context.getString(R.string.other_login_baseurl_not_set))
            return
        }

        val refresh = Refresh().apply {
            clientToken = account.clientToken
            accessToken = account.accessToken
        }

        if (select) {
            val selectedProfile = Refresh.SelectedProfile().apply {
                name = account.username
                id = account.profileId
            }
            refresh.selectedProfile = selectedProfile
        }

        val data = Gson().toJson(refresh)
        callLogin(data, "/authserver/refresh", listener)
    }

    private fun callLogin(data: String, endpoint: String, listener: Listener) {
        val normalizedBaseUrl = baseUrl
        if (normalizedBaseUrl.isNullOrBlank()) {
            listener.onFailed("Base URL is not set.")
            return
        }

        val finalUrl = normalizedBaseUrl + endpoint
        val body = data.toRequestBody("application/json; charset=utf-8".toMediaType())

        Logging.i(TAG, "Request URL: $finalUrl")
        Logging.i(TAG, "Request body: $data")

        val request = createRequestBuilder(finalUrl, body)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()

            Logging.i(TAG, "Response code: ${response.code}")
            Logging.i(TAG, "Response body: $responseBody")

            if (response.isSuccessful) {
                val result = Gson().fromJson(responseBody, AuthResult::class.java)
                listener.onSuccess(result)
                return
            }

            var errorMessage = responseBody.ifBlank { "Unknown error" }

            runCatching {
                val jsonObject = JSONObject(responseBody)
                errorMessage = when {
                    jsonObject.has("errorMessage") -> jsonObject.getString("errorMessage")
                    jsonObject.has("message") -> jsonObject.getString("message")
                    jsonObject.has("error") -> jsonObject.getString("error")
                    else -> errorMessage
                }

                if (errorMessage.contains("\\u")) {
                    errorMessage = StringUtilsKt.decodeUnicode(
                        errorMessage.replace("\\\\u", "\\u")
                    )
                }
            }.onFailure { e ->
                Logging.e(TAG, Tools.printToString(e))
            }

            listener.onFailed("(${response.code}) $errorMessage")
        }
    }

    fun getServeInfo(context: Context, url: String): String? {
        val normalizedUrl = normalizeBaseUrl(url)
        Logging.i(TAG, "Fetching server info from: $normalizedUrl")

        val request = createRequestBuilder(normalizedUrl)
            .get()
            .header("Accept", "application/json")
            .build()

        val call = client.newCall(request)

        runCatching {
            call.execute().use { response ->
                val res = response.body?.string()
                Logging.i(TAG, "Server info code: ${response.code}")
                Logging.i(TAG, "Server info body: $res")

                if (response.isSuccessful) return res
            }
        }.onFailure { e ->
            Tools.showError(context, e)
        }

        return null
    }

    private fun normalizeBaseUrl(input: String): String {
        var url = input.trim().removeSuffix("/")

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        val lowercaseUrl = url.lowercase(Locale.ROOT)

        // Ely.by website aliases should always be converted to the launcher auth server.
        if (
            lowercaseUrl == "https://ely.by" ||
            lowercaseUrl == "http://ely.by" ||
            lowercaseUrl == "https://www.ely.by" ||
            lowercaseUrl == "http://www.ely.by" ||
            lowercaseUrl == "https://account.ely.by" ||
            lowercaseUrl == "http://account.ely.by"
        ) {
            return ELY_BY_AUTH_BASE
        }

        // Prevent duplicated /authserver paths.
        if (lowercaseUrl.endsWith("/authserver")) {
            return url.removeSuffix("/authserver")
        }

        return url
    }

    interface Listener {
        fun onSuccess(authResult: AuthResult)
        fun onFailed(error: String)
    }
}