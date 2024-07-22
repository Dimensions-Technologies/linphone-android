package org.linphone.activities.main.configuration

data class UserDeviceDTO(
    val deviceId: String,
    val pbxId: String,
    val sipUserName: String,
    val sipUserPassword: String,
    val sipRealm: String,
    val sipOutboundProxy: String,
    val sipOutboundProxyPort: Int,
    val sipTransport: String,
    val sipRegisterTimeout: Int,
    val primaryDns: String,
    val secondaryDns: String,
    val callWaitingEnabled: Boolean?
)