package io.shinara.shinarakotlinsdk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class ShinaraSDK private constructor() {
    companion object {
        val instance: ShinaraSDK by lazy { ShinaraSDK() }
    }

    @Serializable
    data class TrackingSessionData(
        @SerialName("session_id") val sessionId: String,
        @SerialName("user_agent") val userAgent: String,
        @SerialName("device_model") val deviceModel: String,
        @SerialName("os_version") val osVersion: String,
        @SerialName("screen_resolution") val screenResolution: String,
        val timezone: String,
        val language: String?
    )

    @Serializable
    data class KeyValidationResponse(
        @SerialName("app_id") val appId: String,
        @SerialName("track_retention") val trackRetention: Boolean? = null
    )

    @Serializable
    data class ValidationResponse(
        @SerialName("campaign_id") val programId: String? = null,
        @SerialName("affiliate_code_id") val codeId: String? = null
    )

    @Serializable
    data class CodeValidationRequest(val code: String)

    @Serializable
    data class ConversionUser(
        @SerialName("external_user_id") val externalUserId: String,
        val name: String? = null,
        val email: String? = null,
        val phone: String? = null,
        @SerialName("auto_generated_external_user_id") val autoGeneratedExternalUserId: String? = null
    )

    @Serializable
    data class TriggerAppOpenRequest(
        @SerialName("affiliate_code_id") val codeId: String,
        @SerialName("external_user_id") val externalUserId: String? = null,
        @SerialName("auto_generated_external_user_id") val autoGeneratedExternalUserId: String? = null
    )

    @Serializable
    data class UserRegistrationRequest(
        val code: String,
        val platform: String,
        @SerialName("conversion_user") val conversionUser: ConversionUser,
        @SerialName("affiliate_code_id") val codeId: String? = null
    )

    @Serializable
    data class PurchaseRequest(
        @SerialName("product_id") val productId: String,
        @SerialName("transaction_id") val transactionId: String,
        val code: String,
        val platform: String = "",
        val token: String? = null,
        @SerialName("affiliate_code_id") val affiliateCodeId: String? = null,
        @SerialName("external_user_id") val externalUserId: String? = null,
        @SerialName("auto_generated_external_user_id") val autoGeneratedExternalUserId: String? = null,
    )

    private var apiKey: String? = null
    private val baseURL = "https://sdk-gateway-b85kv8d1.ue.gateway.dev"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val sdkSetupCompletedKey = "SHINARA_SDK_SETUP_COMPLETED"
    private val referralCodeKey = "SHINARA_SDK_REFERRAL_CODE"
    private val programIdKey = "SHINARA_SDK_PROGRAM_ID"
    private val referralCodeIdKey = "SHINARA_SDK_REFERRAL_CODE_ID"
    private val userExternalIdKey = "SHINARA_SDK_EXTERNAL_USER_ID"
    private val autoGenUserExternalIdKey = "SHINARA_SDK_AUTO_GEN_EXTERNAL_USER_ID"
    private val processedTransactionsKey = "SHINARA_SDK_PROCESSED_TRANSACTIONS"
    private val registeredUsersKey = "SHINARA_SDK_REGISTERED_USERS"

    private val apiHeaderKey = "X-API-Key"
    private val sdkPlatformHeaderKey = "X-SDK-Platform"
    private val sdkPlatformHeaderValue = "android"

    private val referralParamKey = "shinara_ref_code"

    private lateinit var context: Context
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("YourPreferenceName", Context.MODE_PRIVATE)
    }

    public suspend fun initialize(appContext: Context, apiKey: String) {
        context = appContext
        this.apiKey = apiKey
        val validationResponse = validateAPIKey()
        triggerSetup()
        if (validationResponse.trackRetention != null && validationResponse.trackRetention) {
            triggerAppOpen()
        }
        println("Shinara SDK Initialized")
    }

    private suspend fun validateAPIKey(): KeyValidationResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKey ?: throw IllegalStateException("API Key is not set")

        val request = Request.Builder()
            .url("$baseURL/api/key/validate")
            .addHeader(apiHeaderKey, apiKey)
            .addHeader(sdkPlatformHeaderKey, sdkPlatformHeaderValue)
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw IOException("API Key validation failed: ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response body")

        json.decodeFromString(KeyValidationResponse.serializer(), responseBody)
    }

    public suspend fun handleDeepLink(url: String) {
        val uri = Uri.parse(url)
        val referralCode = uri.getQueryParameter(referralParamKey)

        referralCode?.let { code ->
            // Launch a fire-and-forget coroutine for validateReferralCode
            CoroutineScope(Dispatchers.IO).launch {
                validateReferralCode(code)
            }
        }
    }

    public suspend fun validateReferralCode(code: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKey ?: throw IllegalStateException("API Key is not set")

        val requestBody = json.encodeToString(
            CodeValidationRequest.serializer(),
            CodeValidationRequest(code)
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseURL/api/code/validate")
            .post(requestBody)
            .addHeader(apiHeaderKey, apiKey)
            .addHeader(sdkPlatformHeaderKey, sdkPlatformHeaderValue)
            .build()

        val response = client.newCall(request).await()

        if (!response.isSuccessful) {
            throw IOException("Referral code validation failed: ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response body")

        val validationResponse = json.decodeFromString(
            ValidationResponse.serializer(),
            responseBody
        )

        val programId = validationResponse.programId
            ?: throw IllegalStateException("Invalid program ID")

        sharedPreferences.edit().apply {
            putString(referralCodeKey, code)
            putString(programIdKey, programId)
            validationResponse.codeId?.let {
                putString(referralCodeIdKey, it)
            }
        }.apply()

        programId
    }

    private suspend fun triggerSetup() = withContext(Dispatchers.IO) {
        val apiKey = apiKey ?: return@withContext

        if (sharedPreferences.contains(sdkSetupCompletedKey)) {
            return@withContext
        }

        val autoSDKGenExternalUserId = sharedPreferences.getString(autoGenUserExternalIdKey, null)
            ?: UUID.randomUUID().toString().also {
                sharedPreferences.edit().putString(autoGenUserExternalIdKey, it).apply()
            }

        val trackingData = getTrackingSessionData(autoSDKGenExternalUserId)

        val requestBody = json.encodeToString(
            TrackingSessionData.serializer(),
            trackingData
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseURL/sdknewtrackingsession")
            .post(requestBody)
            .addHeader(apiHeaderKey, apiKey)
            .addHeader(sdkPlatformHeaderKey, sdkPlatformHeaderValue)
            .build()

        val response = client.newCall(request).await()

        if (response.isSuccessful) {
            sharedPreferences.edit().putBoolean("SHINARA_SDK_SETUP_COMPLETED", true).apply()
        }
    }

    private suspend fun triggerAppOpen() = withContext(Dispatchers.IO) {
        val apiKey = apiKey ?: return@withContext

        val referralCodeId = sharedPreferences.getString(referralCodeIdKey, null)
            ?: return@withContext

        val externalUserId = sharedPreferences.getString(userExternalIdKey, null)
        val autoGeneratedExternalUserId = sharedPreferences.getString(autoGenUserExternalIdKey, null)

        val request = TriggerAppOpenRequest(
            codeId = referralCodeId,
            externalUserId = externalUserId,
            autoGeneratedExternalUserId = autoGeneratedExternalUserId
        )

        val requestBody = json.encodeToString(
            TriggerAppOpenRequest.serializer(),
            request
        ).toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseURL/appopen")
            .post(requestBody)
            .addHeader(apiHeaderKey, apiKey)
            .addHeader(sdkPlatformHeaderKey, sdkPlatformHeaderValue)
            .build()

        client.newCall(httpRequest).execute()
    }

    public fun getReferralCode(): String? =
        sharedPreferences.getString(referralCodeKey, null)

    public fun getProgramId(): String? =
        sharedPreferences.getString(programIdKey, null)

    public suspend fun registerUser(
        userId: String,
        email: String? = null,
        name: String? = null,
        phone: String? = null
    ) = withContext(Dispatchers.IO) {
        val apiKey = apiKey ?: throw IllegalStateException("API Key is not set")

        val referralCode = sharedPreferences.getString(referralCodeKey, null)
            ?: throw IllegalStateException("No stored referral code found")

        val registeredUsers = sharedPreferences.getStringSet(registeredUsersKey, mutableSetOf())
        if (userId in registeredUsers!!) return@withContext

        val conversionUser = ConversionUser(
            externalUserId = userId,
            name = name,
            email = email,
            phone = phone,
            autoGeneratedExternalUserId = sharedPreferences.getString(autoGenUserExternalIdKey, null)
        )

        val codeId = sharedPreferences.getString(referralCodeIdKey, null)

        val request = UserRegistrationRequest(
            code = referralCode,
            platform = "",
            conversionUser = conversionUser,
            codeId = codeId
        )

        val requestBody = json.encodeToString(
            UserRegistrationRequest.serializer(),
            request
        ).toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseURL/newuser")
            .post(requestBody)
            .addHeader(apiHeaderKey, apiKey)
            .addHeader(sdkPlatformHeaderKey, sdkPlatformHeaderValue)
            .build()

        val response = client.newCall(httpRequest).await()

        if (!response.isSuccessful) {
            throw IOException("User registration failed: ${response.code}")
        }

        sharedPreferences.edit().apply {
            putString(userExternalIdKey, userId)
            remove(autoGenUserExternalIdKey)
            val updatedUsers = registeredUsers.toMutableSet().apply { add(userId) }
            putStringSet(registeredUsersKey, updatedUsers)
        }.apply()
    }

    public fun getUserId(): String? =
        sharedPreferences.getString(userExternalIdKey, null)

    public suspend fun attributePurchase(productId: String, transactionId: String, token: String) = withContext(Dispatchers.IO) {
        val apiKey = apiKey ?: return@withContext

        val referralCode = sharedPreferences.getString(referralCodeKey, null)
            ?: // If no referral code, we can skip purchase attribution
            return@withContext

        val processedTransactions = sharedPreferences.getStringSet(processedTransactionsKey, mutableSetOf())
        if (transactionId in processedTransactions!!) return@withContext

        val externalUserId = sharedPreferences.getString(userExternalIdKey, null)
        var autoSDKGenExternalUserId: String? = null
        if (externalUserId == null) {
            autoSDKGenExternalUserId = UUID.randomUUID().toString()
            sharedPreferences.edit().putString(autoGenUserExternalIdKey, autoSDKGenExternalUserId).apply()
        }

        val request = PurchaseRequest(
            productId = productId,
            transactionId = transactionId,
            token = token,
            code = referralCode,
            affiliateCodeId = sharedPreferences.getString(referralCodeIdKey, null),
            externalUserId = externalUserId,
            autoGeneratedExternalUserId = autoSDKGenExternalUserId,
        )

        val requestBody = json.encodeToString(
            PurchaseRequest.serializer(),
            request
        ).toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("$baseURL/iappurchase")
            .post(requestBody)
            .addHeader(apiHeaderKey, apiKey)
            .addHeader(sdkPlatformHeaderKey, sdkPlatformHeaderValue)
            .build()

        val response = client.newCall(httpRequest).await()

        if (!response.isSuccessful) {
            throw IOException("Purchase attribution failed: ${response.code}")
        }

        sharedPreferences.edit().apply {
            val updatedTransactions = processedTransactions.toMutableSet().apply { add(transactionId) }
            putStringSet(processedTransactionsKey, updatedTransactions)
        }.apply()
    }

    private suspend fun Call.await(): Response {
        return suspendCoroutine { continuation ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
        }
    }

    // Add this function inside your ShinaraSDK class
    private fun getTrackingSessionData(sessionId: String): TrackingSessionData {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * displayMetrics.density).toInt()
        val height = (displayMetrics.heightPixels * displayMetrics.density).toInt()

        return TrackingSessionData(
            sessionId = sessionId,
            userAgent = "Android ${android.os.Build.VERSION.RELEASE}",
            deviceModel = android.os.Build.MODEL,
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            screenResolution = "${width}x${height}",
            timezone = TimeZone.getDefault().id,
            language = Locale.getDefault().language
        )
    }
}