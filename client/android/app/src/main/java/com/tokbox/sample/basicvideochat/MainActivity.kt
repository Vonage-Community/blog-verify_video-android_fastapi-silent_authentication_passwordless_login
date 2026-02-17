package com.tokbox.sample.basicvideochat

import android.Manifest
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.opentok.android.BaseVideoRenderer
import com.opentok.android.OpentokError
import com.opentok.android.Publisher
import com.opentok.android.PublisherKit
import com.opentok.android.PublisherKit.PublisherListener
import com.opentok.android.Session
import com.opentok.android.Session.SessionListener
import com.opentok.android.Stream
import com.opentok.android.Subscriber
import com.opentok.android.SubscriberKit
import com.opentok.android.SubscriberKit.SubscriberListener
import com.tokbox.sample.basicvideochat.network.APIService
import com.tokbox.sample.basicvideochat.network.ConfirmVerifyRequest
import com.tokbox.sample.basicvideochat.network.ConfirmVerifyResponse
import com.tokbox.sample.basicvideochat.network.StartVerifyRequest
import com.tokbox.sample.basicvideochat.network.StartVerifyResponse
import com.tokbox.sample.basicvideochat.network.VideoTokenResponse
import com.vonage.clientlibrary.VGCellularRequestClient
import com.vonage.clientlibrary.VGCellularRequestParameters
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import pub.devrel.easypermissions.EasyPermissions.PermissionCallbacks
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MainActivity : AppCompatActivity(), PermissionCallbacks {
    private var retrofit: Retrofit? = null
    private var apiService: APIService? = null
    private var session: Session? = null
    private var publisher: Publisher? = null
    private var subscriber: Subscriber? = null
    private lateinit var publisherViewContainer: FrameLayout
    private lateinit var subscriberViewContainer: FrameLayout
    private var verifyRequestId: String? = null
    private var verifyCheckUrl: String? = null
    private lateinit var phoneInput: EditText
    private lateinit var btnStartVerify: Button
    private lateinit var smsCodeInput: EditText
    private lateinit var btnConfirmSms: Button
    private var isVerifying = false
    private var isConfirmingSms = false

    private val publisherListener: PublisherListener = object : PublisherListener {
        override fun onStreamCreated(publisherKit: PublisherKit, stream: Stream) {
            Log.d(TAG, "onStreamCreated: Publisher Stream Created. Own stream ${stream.streamId}")
        }

        override fun onStreamDestroyed(publisherKit: PublisherKit, stream: Stream) {
            Log.d(TAG, "onStreamDestroyed: Publisher Stream Destroyed. Own stream ${stream.streamId}")
        }

        override fun onError(publisherKit: PublisherKit, opentokError: OpentokError) {
            finishWithMessage("PublisherKit onError: ${opentokError.message}")
        }
    }

    private val sessionListener: SessionListener = object : SessionListener {
        override fun onConnected(session: Session) {
            Log.d(TAG, "onConnected: Connected to session: ${session.sessionId}")
            publisher = Publisher.Builder(this@MainActivity).build()
            publisher?.setPublisherListener(publisherListener)
            publisher?.renderer?.setStyle(BaseVideoRenderer.STYLE_VIDEO_SCALE, BaseVideoRenderer.STYLE_VIDEO_FILL)
            publisherViewContainer.addView(publisher?.view)
            if (publisher?.view is GLSurfaceView) {
                (publisher?.view as GLSurfaceView).setZOrderOnTop(true)
            }
            session.publish(publisher)
        }

        override fun onDisconnected(session: Session) {
            Log.d(TAG, "onDisconnected: Disconnected from session: ${session.sessionId}")
        }

        override fun onStreamReceived(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamReceived: New Stream Received ${stream.streamId} in session: ${session.sessionId}")
            if (subscriber == null) {
                subscriber = Subscriber.Builder(this@MainActivity, stream).build().also {
                    it.renderer?.setStyle(
                        BaseVideoRenderer.STYLE_VIDEO_SCALE,
                        BaseVideoRenderer.STYLE_VIDEO_FILL
                    )

                    it.setSubscriberListener(subscriberListener)
                }

                session.subscribe(subscriber)
                subscriberViewContainer.addView(subscriber?.view)
            }
        }

        override fun onStreamDropped(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamDropped: Stream Dropped: ${stream.streamId} in session: ${session.sessionId}")
            if (subscriber != null) {
                subscriber = null
                subscriberViewContainer.removeAllViews()
            }
        }

        override fun onError(session: Session, opentokError: OpentokError) {
            finishWithMessage("Session error: ${opentokError.message}")
        }
    }

    var subscriberListener: SubscriberListener = object : SubscriberListener {
        override fun onConnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onConnected: Subscriber connected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onDisconnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onDisconnected: Subscriber disconnected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onError(subscriberKit: SubscriberKit, opentokError: OpentokError) {
            finishWithMessage("SubscriberKit onError: ${opentokError.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VGCellularRequestClient.initializeSdk(this.applicationContext)

        setContentView(R.layout.activity_main)
        publisherViewContainer = findViewById(R.id.publisher_container)
        subscriberViewContainer = findViewById(R.id.subscriber_container)
        phoneInput = findViewById(R.id.phone_input)
        btnStartVerify = findViewById(R.id.btn_start_verify)
        smsCodeInput = findViewById(R.id.sms_code_input)
        btnConfirmSms = findViewById(R.id.btn_confirm_sms)

        btnStartVerify.setOnClickListener {
            if (isVerifying) return@setOnClickListener
            val phone = phoneInput.text?.toString()?.trim().orEmpty()
            if (!phone.matches(Regex("^\\+?[1-9][0-9]{6,14}$"))) {
                phoneInput.error = "Phone number must be E.164 (e.g. 44123456789)"
                phoneInput.requestFocus()
                return@setOnClickListener
            }
            val normalized = phone.removePrefix("+")
            hideKeyboard()
            isVerifying = true
            btnStartVerify.isEnabled = false
            startVerify(normalized)
        }

        btnConfirmSms.setOnClickListener {
            if (isConfirmingSms) return@setOnClickListener
            val code = smsCodeInput.text?.toString()?.trim().orEmpty()
            if (!code.matches(Regex("^[0-9]{4}$"))) {
                smsCodeInput.error = "Enter the SMS code (digits only)."
                smsCodeInput.requestFocus()
                return@setOnClickListener
            }
            hideKeyboard()
            isConfirmingSms = true
            btnConfirmSms.isEnabled = false
            confirmSmsCode(code)
        }

        requestPermissions()
    }

    override fun onPause() {
        super.onPause()
        session?.onPause()
    }

    override fun onResume() {
        super.onResume()
        session?.onResume()
    }

    override fun onStop() {
        try {
            publisher?.let { session?.unpublish(it) }
            subscriber?.let { session?.unsubscribe(it) }
            session?.disconnect()
        } finally {
            super.onStop()
        }
    }

    override fun onDestroy() {
        try {
            publisher?.let { session?.unpublish(it) }
            subscriber?.let { session?.unsubscribe(it) }
            session?.disconnect()

            publisher?.destroy()
            subscriber?.destroy()

            publisher = null
            subscriber = null
            session = null
        } finally {
            super.onDestroy()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        Log.d(TAG, "onPermissionsGranted:$requestCode: $perms")
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        finishWithMessage("onPermissionsDenied: $requestCode: $perms")
    }

    @AfterPermissionGranted(PERMISSIONS_REQUEST_CODE)
    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.INTERNET, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (EasyPermissions.hasPermissions(this, *perms)) {
            if (ServerConfig.hasChatServerUrl()) {
                // Custom server URL exists - retrieve session config
                if (!ServerConfig.isValid || !ServerConfig.hasChatServerUrl()) {
                    finishWithMessage("Chat server url is invalid or missing: ${ServerConfig.CHAT_SERVER_URL}")
                    return
                }
                initRetrofit()
            }
        } else {
            EasyPermissions.requestPermissions(
                this,
                getString(R.string.rationale_video_app),
                PERMISSIONS_REQUEST_CODE,
                *perms
            )
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: View(this)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun showSmsUi(show: Boolean) {
        smsCodeInput.visibility = if (show) View.VISIBLE else View.GONE
        btnConfirmSms.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showSilentAuthUi(show: Boolean) {
        phoneInput.visibility = if (show) View.VISIBLE else View.GONE
        btnStartVerify.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun endVerifyUiLock() {
        isVerifying = false
        btnStartVerify.isEnabled = true
    }

    private fun endSmsUiLock() {
        isConfirmingSms = false
        btnConfirmSms.isEnabled = true
    }

    private fun startVerify(phoneNumber: String) {
        Log.i(TAG, "startVerify phone=$phoneNumber")

        apiService?.startVerify(StartVerifyRequest(phone_number = phoneNumber))
            ?.enqueue(object : Callback<StartVerifyResponse> {
                override fun onResponse(
                    call: Call<StartVerifyResponse>,
                    response: Response<StartVerifyResponse>
                ) {
                    if (!response.isSuccessful) {
                        val msg = when (response.code()) {
                            401 -> "Server auth failed. Check backend .env credentials."
                            402 -> "Your balance is low. Please top-up."
                            409 -> "A verification is already in progress. Please wait and try again."
                            422 -> "Check the phone number and try again."
                            429 -> "Reached to rate limit. Please wait and try again."
                            else -> "A verification failed: ${response.code()} ${response.message()}"
                        }
                        phoneInput.error = msg
                        phoneInput.requestFocus()
                        endVerifyUiLock()
                        return
                    }

                    val body = response.body()
                    if (body == null) {
                        endVerifyUiLock()
                        phoneInput.error = "Unexpected response. Please try again."
                        phoneInput.requestFocus()
                        return
                    }

                    verifyRequestId = body.request_id
                    verifyCheckUrl = body.check_url

                    Log.i(TAG, "verify request_id=$verifyRequestId")
                    Log.i(TAG, "verify check_url=$verifyCheckUrl")

                    when (body.channel) {
                        "silent_auth" -> {
                            showSmsUi(false)
                            showSilentAuthUi(show = true)
                            Toast.makeText(this@MainActivity, "Silent Auth Started.", Toast.LENGTH_SHORT).show()
                            runSilentAuthCheck()
                        }
                        "sms" -> {
                            showSmsUi(true)
                            showSilentAuthUi(show = false)
                            endVerifyUiLock()
                            Toast.makeText(this@MainActivity, "Silent Auth not available. Enter the SMS code.", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            endVerifyUiLock()
                            phoneInput.error = "Unexpected response. Please try again. Channel: ${body.channel}"
                            phoneInput.requestFocus()
                        }
                    }
                }

                override fun onFailure(call: Call<StartVerifyResponse>, t: Throwable) {
                    endVerifyUiLock()
                    phoneInput.error = "Network error. Please check your connection and try again. Message: ${t.message}"
                    phoneInput.requestFocus()
                }
            })
    }

    private fun runSilentAuthCheck() {
        val checkUrl = verifyCheckUrl
        if (checkUrl.isNullOrBlank()) {
            endVerifyUiLock()
            phoneInput.error = "Missing Check URL. Please try again."
            phoneInput.requestFocus()
            return
        }

        val params = VGCellularRequestParameters(
            url = checkUrl,
            headers = emptyMap(),
            queryParameters = emptyMap(),
            maxRedirectCount = 10
        )

        Thread {
            try {
                val response = VGCellularRequestClient.getInstance().startCellularGetRequest(
                    params,
                    true
                )

                Log.i(TAG, "SilentAuth response: $response")

                val error = response.optString("error")
                if (error.isNotBlank()) {
                    val desc = response.optString("error_description")
                    Log.e(TAG, "SilentAuth error=$error desc=$desc")

                    runOnUiThread {
                        endVerifyUiLock()
                        phoneInput.error = "Silent Auth could not be completed on this network. Please try again. Error: $error"
                        phoneInput.requestFocus()
                    }
                    return@Thread
                }

                val status = response.optInt("http_status", -1)
                Log.i(TAG, "SilentAuth http_status=$status")

                val jsonBody = response.optJSONObject("response_body")
                val rawBody = response.optString("response_raw_body", "")

                val code = when {
                    jsonBody != null -> jsonBody.optString("code", "")
                    rawBody.isNotBlank() -> try {
                        JSONObject(rawBody).optString("code", "")
                    } catch (_: Exception) {
                        ""
                    }
                    else -> ""
                }

                Log.i(TAG, "SilentAuth code=$code")

                runOnUiThread {
                    if (status == 200 && code.isNotBlank()) {

                        val rid = verifyRequestId
                        if (rid.isNullOrBlank()) {
                            endVerifyUiLock()
                            phoneInput.error = "Missing Request ID. Please try again."
                            phoneInput.requestFocus()
                            return@runOnUiThread
                        }

                        apiService?.confirmVerify(
                            ConfirmVerifyRequest(request_id = rid, code = code)
                        )?.enqueue(object : Callback<ConfirmVerifyResponse> {

                            override fun onResponse(
                                call: Call<ConfirmVerifyResponse>,
                                response: Response<ConfirmVerifyResponse>
                            ) {
                                if (!response.isSuccessful) {
                                    endVerifyUiLock()
                                    phoneInput.error = "Silent Auth failed. Please try again. ${response.code()} ${response.message()}"
                                    phoneInput.requestFocus()
                                    return
                                }

                                val st = response.body()?.status
                                Log.i(TAG, "confirm status=$st")

                                if (st == "completed") {
                                    apiService?.getVideoToken()?.enqueue(object : Callback<VideoTokenResponse> {
                                        override fun onResponse(
                                            call: Call<VideoTokenResponse>,
                                            response: Response<VideoTokenResponse>
                                        ) {
                                            if (!response.isSuccessful) {
                                                endVerifyUiLock()
                                                phoneInput.error = "Video setup failed. Please try again. ${response.code()} ${response.message()}"
                                                phoneInput.requestFocus()
                                                return
                                            }
                                            val body = response.body()
                                            if (body == null) {
                                                endVerifyUiLock()
                                                phoneInput.error = "Missing Video Token. Please try again."
                                                phoneInput.requestFocus()
                                                return
                                            }
                                            Log.i(TAG, "video token fetched. sessionId=${body.sessionId}")
                                            Toast.makeText(this@MainActivity, "Authentication successful. Joining the Video session.", Toast.LENGTH_LONG).show()
                                            initializeSession(body.apiKey, body.sessionId, body.token)
                                        }
                                        override fun onFailure(call: Call<VideoTokenResponse>, t: Throwable) {
                                            endVerifyUiLock()
                                            phoneInput.error = "Network error. Please check your connection and try again. ${t.message}"
                                            phoneInput.requestFocus()
                                        }
                                    })
                                } else {
                                    endVerifyUiLock()
                                    phoneInput.error = "Silent Auth failed. Please try again. Status: $st"
                                    phoneInput.requestFocus()
                                }
                            }

                            override fun onFailure(call: Call<ConfirmVerifyResponse>, t: Throwable) {
                                endVerifyUiLock()
                                phoneInput.error = "Network error. Please check your connection and try again. Message: ${t.message}"
                                phoneInput.requestFocus()
                            }
                        })

                    } else {
                        endVerifyUiLock()
                        phoneInput.error = "Silent Auth failed. Please try again. Status: $status Code: $code"
                        phoneInput.requestFocus()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SilentAuth exception", t)
                runOnUiThread {
                    endVerifyUiLock()
                    Toast.makeText(this, "Silent Auth exception: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun confirmSmsCode(code: String) {
        val rid = verifyRequestId
        if (rid.isNullOrBlank()) {
            endVerifyUiLock()
            showSilentAuthUi(true)
            endSmsUiLock()
            showSmsUi(false)
            phoneInput.error = "Missing Request ID. Please try again."
            phoneInput.requestFocus()
            return
        }

        if (!code.matches(Regex("^[0-9]{4}$"))) {
            endSmsUiLock()
            smsCodeInput.error = "Enter the SMS code (digits only)."
            smsCodeInput.requestFocus()
            return
        }

        apiService?.confirmVerify(ConfirmVerifyRequest(request_id = rid, code = code))
            ?.enqueue(object : Callback<ConfirmVerifyResponse> {

                override fun onResponse(
                    call: Call<ConfirmVerifyResponse>,
                    response: Response<ConfirmVerifyResponse>
                ) {
                    if (!response.isSuccessful) {
                        val msg = when (response.code()) {
                            400, 410 -> "Check the code and try again."
                            401 -> "Server auth failed. Check backend .env credentials."
                            402 -> "Your balance is low. Please top-up."
                            404 -> "Code expired. Please try again."
                            409 -> "A verification is already in progress. Please wait and try again."
                            429 -> "Reached to rate limit. Please wait and try again."
                            else -> "A verification failed: ${response.code()} ${response.message()}"
                        }
                        endSmsUiLock()
                        smsCodeInput.error = msg
                        smsCodeInput.requestFocus()
                        return
                    }

                    val body = response.body()
                    if (body == null) {
                        endSmsUiLock()
                        smsCodeInput.error = "Unexpected response. Please try again."
                        smsCodeInput.requestFocus()
                        return
                    }

                    val st = body.status
                    Log.i(TAG, "verify/confirm status=$st")
                    if (st == "completed") {
                        apiService?.getVideoToken()?.enqueue(object : Callback<VideoTokenResponse> {
                            override fun onResponse(
                                call: Call<VideoTokenResponse>,
                                response: Response<VideoTokenResponse>
                            ) {
                                if (!response.isSuccessful) {
                                    endSmsUiLock()
                                    smsCodeInput.error="Video setup failed. Please try again. ${response.code()} ${response.message()}"
                                    smsCodeInput.requestFocus()
                                    return
                                }
                                val b = response.body()
                                if (b == null) {
                                    endSmsUiLock()
                                    smsCodeInput.error="Missing Video Token. Please try again."
                                    smsCodeInput.requestFocus()
                                    return
                                }
                                Toast.makeText(this@MainActivity, "Authentication successful. Joining the Video session.", Toast.LENGTH_LONG).show()
                                Handler(Looper.getMainLooper()).postDelayed({initializeSession(b.apiKey, b.sessionId, b.token)}, 1000)
                            }

                            override fun onFailure(call: Call<VideoTokenResponse>, t: Throwable) {
                                endSmsUiLock()
                                smsCodeInput.error="Video setup failed. Please try again. ${t.message}"
                                smsCodeInput.requestFocus()
                            }
                        })
                    } else {
                        endSmsUiLock()
                        smsCodeInput.error = "Status: $st"
                        smsCodeInput.requestFocus()
                    }
                }

                override fun onFailure(call: Call<ConfirmVerifyResponse>, t: Throwable) {
                    endSmsUiLock()
                    smsCodeInput.error = "Network error. Please try again."
                    smsCodeInput.requestFocus()
                }
            })
    }

    private fun initializeSession(apiKey: String, sessionId: String, token: String) {
        Log.i(TAG, "apiKey: $apiKey")
        Log.i(TAG, "sessionId: $sessionId")
        Log.i(TAG, "token: $token")

        /*
        The context used depends on the specific use case, but usually, it is desired for the session to
        live outside of the Activity e.g: live between activities. For a production applications,
        it's convenient to use Application context instead of Activity context.
         */
        session = Session.Builder(this, apiKey, sessionId).build().also {
            it.setSessionListener(sessionListener)
            it.connect(token)
        }
    }

    private fun initRetrofit() {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(ServerConfig.CHAT_SERVER_URL)
            .addConverterFactory(MoshiConverterFactory.create())
            .client(client)
            .build().also {
                apiService = it.create(APIService::class.java)
            }
    }

    private fun finishWithMessage(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val PERMISSIONS_REQUEST_CODE = 124
    }
}