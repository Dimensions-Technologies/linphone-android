package org.linphone.services

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.BuildConfig
import org.linphone.authentication.AuthStateManager
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.middleware.FileTree
import org.linphone.utils.Log
import timber.log.Timber

class DiagnosticsService {

    companion object {
        private val sendToSupportGuard = SendToSupportGuard()

        // Shared by About and the sign-in screen, so an upload started on one shows on the other
        private val sendToSupportStateData = MutableLiveData(SendToSupportState.IDLE)
        val sendToSupportState: LiveData<SendToSupportState> = sendToSupportStateData

        /**
         * android_{tenantId}_{yyyyMMdd-HHmmss}.zip, in local time, matching the web client's
         * webrtc_… and the portals' names so support can find a tenant's uploads together. The
         * server doesn't record who uploaded a file, so the name is all support has to go on.
         */
        fun supportFileName(tenantId: String?, now: Date, timeZone: TimeZone = TimeZone.getDefault()): String {
            val tenant = (tenantId ?: "").lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "unknown-tenant" }
            val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            format.timeZone = timeZone
            return "android_${tenant}_${format.format(now)}.zip"
        }

        /**
         * Uploads the logs zip to the support team's container. Taps while an upload is running
         * are ignored, and taps soon after a successful one report it as sent again.
         */
        suspend fun sendToSupport(context: Context) {
            val sent = sendToSupportGuard.run {
                sendToSupportStateData.postValue(SendToSupportState.SENDING)
                try {
                    uploadDiagnostics(context)
                    Log.i("[Diagnostics] Logs sent to support")
                    true
                } catch (e: Exception) {
                    Log.e("[Diagnostics] Failed to send logs to support, ${e.message}")
                    false
                }
            } ?: return

            sendToSupportStateData.postValue(
                if (sent) SendToSupportState.SENT else SendToSupportState.FAILED
            )
        }

        // A screen opened later starts from the plain button, not an old result
        fun clearSendToSupportResult() {
            if (sendToSupportStateData.value != SendToSupportState.SENDING) {
                sendToSupportStateData.value = SendToSupportState.IDLE
            }
        }

        fun clearLogs(context: Context) {
            val logsFolder: String = FileTree.getLogsDirectory(context)
            File(logsFolder).walkTopDown()
                .filter { file -> file.isFile && (file.extension == "log" || file.extension == "json" || file.extension == "zip") }
                .forEach { file -> file.delete() }

            val linphoneLogFiles = getLinphoneLogs(context)
            if (linphoneLogFiles != null) {
                linphoneLogFiles.sortByDescending { f -> f.lastModified() }
                for ((index, file) in linphoneLogFiles.withIndex()) {
                    if (index == 0) {
                        FileWriter(file).close()
                    } else {
                        file.delete()
                    }
                }
            }
        }

        fun getLinphoneLogs(context: Context): Array<File>? {
            val fileFolder = File(context.filesDir.absolutePath)
            return fileFolder.listFiles { directory, filename ->
                directory.length() > 0 && filename.matches(Regex("^linphone\\d+\\.log\$"))
            }
        }

        fun getLogsZip(context: Context): File {
            val logsFolder: String = FileTree.getLogsDirectory(context)

            val linphoneLogFiles = getLinphoneLogs(context)
            if (linphoneLogFiles != null) {
                for (linphoneLogFile in linphoneLogFiles) {
                    linphoneLogFile.copyTo(File(logsFolder + "/" + linphoneLogFile.name), true)
                }
            }

            writeDiagnosticsFile(context)

            val zipFile = FileTree.zipAll(logsFolder)

            // Flush current log files
            Timber.forest().forEach { t -> if (t is FileTree) t.flush() }

            Log.i("Uploading logs from file ${zipFile.path}")

            return zipFile
        }

        private suspend fun uploadDiagnostics(context: Context) {
            val zipFile = getLogsZip(context)

            val body = zipFile
                .readBytes()
                .toRequestBody(
                    "application/octet".toMediaTypeOrNull(),
                    0,
                    zipFile.length().toInt()
                )

            // The user may not be loaded on the sign-in screen, which the file name allows for
            val uploadName = supportFileName(AuthStateManager.getInstance(context).getUser()?.tenantId, Date())

            val response = APIClientService(context).getUCGatewayService().postClientDiagnostics(
                uploadName,
                body
            )
            if (!response.isSuccessful) {
                // The caller logs this once, so it isn't logged here too
                throw Exception(describeUploadFailure(response.code(), response.errorBody()?.string()))
            }
        }

        // Keeps the server's reason for a failed upload, so it reaches the logs and the user
        fun describeUploadFailure(code: Int, errorBody: String?): String {
            val reason = errorBody?.trim()?.replace(Regex("\\s+"), " ")?.take(200)
            return if (reason.isNullOrEmpty()) "HTTP $code" else "HTTP $code: $reason"
        }

        private fun writeDiagnosticsFile(context: Context) {
            val info = JSONObject()
            info.put("productName", "Dimensions Connect Mobile")

            info.put("user", getUserInfo(context))
            info.put(
                "environment",
                DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()?.name
            )
            info.put("version", BuildConfig.VERSION_CODE)
            info.put("versionName", BuildConfig.VERSION_NAME)
            info.put("localTime", Date().toString())

            info.put("timeZone", TimeZone.getDefault().displayName)
            info.put("culture", Locale.getDefault().displayName)
            info.put("sysInfo", getSystemInfo())
            info.put("audioDevices", getAudioDevices(context))

            // Write the JSON to the logs folder
            val logsFolder: String = FileTree.getLogsDirectory(context)
            val diagnosticInfoFile = File("$logsFolder/diagnostic-info.json")
            val output: Writer = BufferedWriter(FileWriter(diagnosticInfoFile, false))
            output.write(info.toString(3))
            output.close()
        }
        private fun getUserInfo(context: Context): JSONObject {
            val user = AuthStateManager.getInstance(context).getUser()

            val jObj = JSONObject()

            if (user != null) {
                jObj.put("name", user.name)
                jObj.put("id", user.id)
                jObj.put("tenantId", user.tenantId)
                jObj.put("tenantName", user.tenantName)
                jObj.put("tenantTier", user.tenantTier)
            }

            return jObj
        }

        private fun getSystemInfo(): JSONObject {
            val jObj = JSONObject()
            jObj.put("manufacturer", Build.MANUFACTURER)
            jObj.put("product", Build.PRODUCT)
            jObj.put("model", Build.MODEL)
            jObj.put("version", System.getProperty("os.version"))
            jObj.put("sdk_version", Build.VERSION.SDK)

            val fields = Build.VERSION_CODES::class.java.fields
            var osCode = "UNKNOWN"
            fields.filter { it.getInt(Build.VERSION_CODES::class) == Build.VERSION.SDK_INT }
                .forEach { osCode = it.name }

            jObj.put("OS", "Android $osCode")

            return jObj
        }

        private fun getAudioDevices(context: Context): JSONArray {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            return JSONArray(audioDevices.toList())
        }
    }
}
