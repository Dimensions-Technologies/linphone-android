import com.google.gson.annotations.SerializedName
import java.util.Date

data class ReportResult(
    @SerializedName("dateCreated")
    val dateCreated: Date,

    @SerializedName("requestId")
    val requestId: String,

    @SerializedName("reportId")
    val reportId: String,

    @SerializedName("status")
    val status: Int,

    @SerializedName("statusMessage")
    val statusMessage: String,

    @SerializedName("userId")
    val userId: String,

    @SerializedName("data")
    val data: Any
)
