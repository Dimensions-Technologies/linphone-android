package org.linphone.activities.main.configuration

data class EnvironmentSettings(
    val id: String,
    val name: String,
    val isDefault: Boolean? = false,
    val isHidden: Boolean? = false,
    val associatedCultures: Array<String>?,
    val identityServerUri: String,
    val gatewayApiUri: String,
    val realtimeApiUri: String,
    val documentationUri: String,
    val diagnosticsBlobConnectionString: String,
    val resourcesBlobUrl: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EnvironmentSettings

        if (id != other.id) return false
        if (name != other.name) return false
        if (isDefault != other.isDefault) return false
        if (isHidden != other.isHidden) return false
        if (!associatedCultures.contentEquals(other.associatedCultures)) return false
        if (identityServerUri != other.identityServerUri) return false
        if (gatewayApiUri != other.gatewayApiUri) return false
        if (realtimeApiUri != other.realtimeApiUri) return false
        if (documentationUri != other.documentationUri) return false
        if (diagnosticsBlobConnectionString != other.diagnosticsBlobConnectionString) return false
        if (resourcesBlobUrl != other.resourcesBlobUrl) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (isDefault?.hashCode() ?: 0)
        result = 31 * result + (isHidden?.hashCode() ?: 0)
        result = 31 * result + associatedCultures.contentHashCode()
        result = 31 * result + identityServerUri.hashCode()
        result = 31 * result + gatewayApiUri.hashCode()
        result = 31 * result + realtimeApiUri.hashCode()
        result = 31 * result + documentationUri.hashCode()
        result = 31 * result + diagnosticsBlobConnectionString.hashCode()
        result = 31 * result + resourcesBlobUrl.hashCode()
        return result
    }
}
