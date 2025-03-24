package org.linphone.models.callhistory

enum class CallDirections(val callDirectionValue: Int) {
    Unknown(0),
    Internal(1),
    Incoming(2),
    Outgoing(3),
    Both(4)
}
