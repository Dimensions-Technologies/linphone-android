package org.linphone.services
import android.content.Context
import android.os.Environment
import com.azure.storage.blob.BlobContainerClient
import com.azure.storage.blob.BlobServiceClientBuilder
import org.linphone.environment.DimensionsEnvironmentService

class DiagnosticsService(context: Context) {

    private val blobContainerClient: BlobContainerClient

    init {
        val env = DimensionsEnvironmentService.getInstance(context).getCurrentEnvironment()
            ?: throw NullPointerException("No environment selected.")

        val connectionString = env.diagnosticsBlobConnectionString
        // val connectionString = "DefaultEndpointsProtocol=https;AccountName=your_storage_account_name;AccountKey=your_storage_account_key;EndpointSuffix=core.windows.net"

        val blobServiceClient = BlobServiceClientBuilder().connectionString(connectionString).buildClient()

        blobContainerClient = blobServiceClient.getBlobContainerClient("uconnect")
        blobContainerClient.createIfNotExists()
    }

    fun uploadDiagnostics() {
        try {
            val localFilePath = saveLogsToLocalFolder()

            val blobClient = blobContainerClient.getBlobClient("logcat.txt")
            blobClient.uploadFromFile(localFilePath)
        } catch (ex: Exception) {
            System.err.printf("Failed to upload from file: %s%n", ex.message)
        }
    }

    private fun saveLogsToLocalFolder(): String {
        val filePath = Environment.getExternalStorageDirectory().toString() + "/logcat.txt"
        Runtime.getRuntime().exec(arrayOf<String>("logcat", "-f", filePath, "MyAppTAG:V", "*:S"))

        return filePath
    }
}
