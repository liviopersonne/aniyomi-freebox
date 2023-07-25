package eu.kanade.tachiyomi.util.cast

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


object HttpFreeboxService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // 0 -> Disconnected ; 1 -> Pending ; 2 -> Connected but no Freebox Player ; 3 -> Connected
    var state: Int = 0
    var freebox: Freebox? = null
    var appToken: String? = null
    var sessionToken: String? = null
    var track_id: Int? = null

    private const val APP_ID = "ani"
    private const val APP_NAME = "Aniyomi"
    private const val APP_VERSION = "1.0"
    private const val DEVICE_NAME = "Smartphone"
    const val SESSION_TOKEN_HEADER = "X-Fbx-App-Auth"

    private fun apiCall(api_url: String): String? {
        if (freebox == null) { return null }
        val f = freebox!!
        return "http://mafreebox.freebox.fr${f.api_base_url}v${f.api_version.substringBefore(".")}/$api_url"
    }

    private fun getPassword(challenge: String): String {
        if (appToken == null) {
            return ""
        }
        val appTokenBytes = appToken!!.toByteArray(StandardCharsets.UTF_8)
        val challengeBytes = challenge.toByteArray(StandardCharsets.UTF_8)
        val secretKey = SecretKeySpec(appTokenBytes, "HmacSHA1")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(challengeBytes)
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    // Start state: 0
    suspend fun searchFreebox(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val body = client.newCall(GET("http://mafreebox.freebox.fr/api_version"))
                    .execute().body.string()
                freebox = json.decodeFromString<Freebox>(body)
                Log.d("Freebox", "Found freebox: $freebox")
                true
            }
        } catch (e: Exception) {
            Log.d("Freebox", "Error fetching Freebox server: $e")
            false
        }
    }

    // Start state: 0
    suspend fun getAppToken(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val requestBody = json.encodeToString(TokenRequest(APP_ID, APP_NAME, APP_VERSION, DEVICE_NAME))
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(apiCall("login/authorize/")!!)
                    .post(requestBody.toRequestBody(mediaType))
                    .build()
                val body = client.newCall(request).execute().body.string()
                val content = json.decodeFromString<FreeboxResponse>(body)
                if (!content.success) { Log.d("Freebox", "Error in request: ${content.error_code}, ${content.msg}") ; return@withContext false }
                appToken = content.result.app_token
                track_id = content.result.track_id
                Log.d("Freebox", "Got app token: $appToken")
                state = 1
                true
            }
        } catch (e: Exception) {
            Log.d("Freebox", "Error in App Token Request: $e")
            false
        }
    }

    // Start state: 1
    suspend fun appTokenValid(): Int {
        if(track_id == null) { return -1 }
        return try {
            withContext(Dispatchers.IO) {
                val body = client.newCall(GET(apiCall("login/authorize/$track_id")!!))
                    .execute().body.string()
                val content = json.decodeFromString<FreeboxResponse>(body)
                if (!content.success) {
                    return@withContext -1
                }
                Log.d("Freebox", "Status: ${content.result.status}")
                when (content.result.status) {
                    "pending" -> 0
                    "granted" -> 1
                    else -> -1
                }
            }
        } catch(e: Exception) {
            Log.d("Freebox", "Error verifying token validity: ${e.message}")
            -1
        }
    }

    // Start state: 1
    suspend fun getSessionToken(challenge: String? = null): Boolean {
        if(appToken == null) { state = 0; return false }
        return try {
            withContext(Dispatchers.IO) {
                val newChallenge = challenge ?: run {
                    val body = client.newCall(GET(apiCall("login/")!!))
                        .execute().body.string()
                    json.decodeFromString<FreeboxResponse>(body).result.challenge
                }
                val requestBody = json.encodeToString(SessionStartRequest(getPassword(newChallenge), APP_ID, APP_VERSION))
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(apiCall("login/session/")!!)
                    .post(requestBody.toRequestBody(mediaType))
                    .build()
                val body = client.newCall(request).execute().body.string()
                val content = json.decodeFromString<FreeboxResponse>(body)
                if (!content.success) {
                    Log.d("Freebox", "Error in request: ${content.error_code}, ${content.msg}")
                    state = 0
                    return@withContext false
                }
                sessionToken = content.result.session_token
                Log.d("Freebox", "Got session token !")
                state = 2
                true
            }
        } catch(e: Exception) {
            Log.d("Freebox", "Error getting refresh token: $e")
            state = 0
            false
        }
    }

    // Start state: 2 or 3
    suspend fun logout(): Boolean {
        if(sessionToken == null) { state = 0; return true }
        return try {
            withContext(Dispatchers.IO) {
                //Post request
                val request = Request.Builder()
                    .method("POST", RequestBody.create(null, ByteArray(0)))
                    .url(apiCall("login/logout/")!!)
                    .header(SESSION_TOKEN_HEADER, sessionToken!!)
                    .build()
                val body = client.newCall(request).execute().body.string()
                val content = json.decodeFromString<FreeboxResponse>(body)
                if (content.success) {
                    Log.d("Freebox", "Session Disconnected")
                    state = 0
                    true
                } else {
                    Log.d("Freebox", "Logout failed: ${content.error_code}, ${content.msg}")
                    false
                }
            }
        } catch(e: Exception) {
            Log.d("Freebox", "Exception during logout: ${e.message}")
            false
        }
    }

    // Start state: 2
    suspend fun getReceiversAirmedia(): List<AirmediaReceiver> {
        if(sessionToken == null) { state = 0; return emptyList() }
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(apiCall("airmedia/receivers/")!!)
                    .header(SESSION_TOKEN_HEADER, sessionToken!!)
                    .build()
                val body = client.newCall(request).execute().body.string()
                val content = json.decodeFromString<AirmediaReceiverResponse>(body)
                Log.d("Freebox", "Airmedia Config Body: $body")
                content.result
            }
        } catch(e: Exception) {
            Log.d("Freebox", "Error fetching Airmedia Receivers: $e")
            emptyList()
        }
    }

    // Start state: 2
    suspend fun freeboxPlayerAvailable(): Int {
        val receivers = getReceiversAirmedia()
        for(r in receivers) {
            if(r.name == "Freebox Player") {
                return if(!r.password_protected) {
                    state = 3
                    1
                } else {
                    0
                }
            }
        }
        return -1
    }

    // Start state: 3
    suspend fun playVideo(url: String): Boolean {
        if(freeboxPlayerAvailable() != 1) { state = 2; return false }
        return try {
            withContext(Dispatchers.IO) {
                val requestBody = json.encodeToString(AirmediaReceiverRequest("start", "video", url))
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(apiCall("airmedia/receivers/Freebox Player")!!)
                    .post(requestBody.toRequestBody(mediaType))
                    .header(SESSION_TOKEN_HEADER, sessionToken!!)
                    .build()
                val body = client.newCall(request).execute().body.string()
                val content = json.decodeFromString<FreeboxResponse>(body)
                content.success
            }
        } catch(e: Exception) {
            Log.d("Freebox", "Error Starting Video: $e")
            false
        }
    }

    // Start state: 3
    suspend fun stopVideo(url: String): Boolean {
        if(freeboxPlayerAvailable() != 1) { state = 2; return false }
        return try {
            withContext(Dispatchers.IO) {
                val requestBody = json.encodeToString(AirmediaReceiverRequest("stop", "video"))
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(apiCall("airmedia/receivers/Freebox Player")!!)
                    .post(requestBody.toRequestBody(mediaType))
                    .header(SESSION_TOKEN_HEADER, sessionToken!!)
                    .build()
                val body = client.newCall(request).execute().body.string()
                val content = json.decodeFromString<FreeboxResponse>(body)
                content.success
            }
        } catch(e: Exception) {
            Log.d("Freebox", "Error Stopping Video: $e")
            false
        }
    }


    @Serializable
    data class TokenRequest(
        val app_id: String,
        val app_name: String,
        val app_version: String,
        val device_name: String,
    )

    @Serializable
    data class SessionStartRequest(
        val password: String,
        val app_id: String,
        val app_version: String,
    )

    @Serializable
    data class FreeboxResponse(
        val success: Boolean = false,
        val result: FreeboxResult = FreeboxResult(),
        val msg: String = "",
        val error_code: String = "",
    )

    @Serializable
    data class FreeboxResult(
        val app_token: String = "",
        val session_token: String = "",
        val track_id: Int = 0,
        val status: String = "",
        val challenge: String = "",
        val enabled: Boolean = false,
    )

    @Serializable
    data class AirmediaConfig(
        val enabled: Boolean,
        val password: String,
    )

    @Serializable
    data class AirmediaReceiver(
        val name: String,
        val password_protected: Boolean,
        val capabilities: Capabilities,
    )

    @Serializable
    data class Capabilities(
        val photo: Boolean = false,
        val audio: Boolean = false,
        val video: Boolean = false,
        val screen: Boolean = false,
    )

    @Serializable
    data class AirmediaReceiverResponse(
        val success: Boolean = false,
        val result: List<AirmediaReceiver> = emptyList(),
        val msg: String = "",
        val error_code: String = "",
    )

    @Serializable
    data class AirmediaReceiverRequest(
        val action: String, // start or stop
        val media_type: String, // photo or video
        val media: String = "",
        val position: Int = 0,
        val password: String = "",
    )

    @Serializable
    data class Freebox(
        val uid: String,
        val device_name: String,
        val api_version: String,
        val api_base_url: String,
        val device_type: String,
        val api_domain: String,
        val https_available: Boolean,
        val https_port: Int,
    )
}
