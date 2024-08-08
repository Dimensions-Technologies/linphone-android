package org.linphone.services

// import com.azure.storage.blob.BlobContainerClient
// import com.azure.storage.blob.BlobServiceClientBuilder
import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.linphone.core.tools.Log
import org.linphone.environment.DimensionsEnvironmentService
import org.linphone.interfaces.CTGatewayService
import org.linphone.middleware.FileTree
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DiagnosticsService(context: Context) {

    // private val blobContainerClient: BlobContainerClient
    // private val blobContainerRef: CloudBlobContainer

    private val logsFolder: String
    private val gatewayService: CTGatewayService

    init {

        val env = DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()
            ?: throw NullPointerException("No environment selected.")
        /*
        val storageAccount = CloudStorageAccount.parse(env.diagnosticsBlobConnectionString)
        val credentials = StorageCredentials.tryParseCredentials(
            env.diagnosticsBlobConnectionString
        )
        val account = CloudStorageAccount(credentials)

        val blobClient = account.createCloudBlobClient()
        blobContainerRef = blobClient.getContainerReference("uconnect")
        */

        gatewayService = APIClientService()
            .getUCGatewayService(context, env.gatewayApiUri)

        logsFolder = FileTree.getLogsDirectory(context)
    }

    fun uploadDiagnostics() {
        try {
            val fileList = File(logsFolder).listFiles()

            val file = fileList?.first() ?: throw FileNotFoundException("No log file found")

            Log.i("Uploading logs from file " + file.path)

            /*
            val blockRef = blobContainerRef.getBlockBlobReference(firstFile.name)
            blockRef.uploadFromFile(firstFile.path)
            */

            // val body: Part = createFormData.createFormData("image", file.name, requestFile)
            val body = file.readBytes()
                .toRequestBody(
                    "application/octet".toMediaTypeOrNull(),
                    0,
                    file.length().toInt()
                )

            val response = gatewayService.doPostClientDiagnostics(file.name, body)
                .enqueue(object : Callback<ResponseBody> {
                    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                        Log.i("Failed to upload logs", t)
                    }

                    override fun onResponse(
                        call: Call<ResponseBody>,
                        response: Response<ResponseBody>
                    ) {
                        var json = response.body().toString()
                        val code = response.code()
                        if (code < 200 || code > 299) {
                            Log.e("Failed to upload logs: " + json)
                        } else {
                            Log.i("Logs uploaded! " + json)
                        }
                    }
                })
        } catch (ex: Exception) {
            Log.e("Error uploading logs", ex)
        }
    }
}
