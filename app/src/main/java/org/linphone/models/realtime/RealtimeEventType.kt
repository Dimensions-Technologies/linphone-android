package org.linphone.models.realtime

enum class RealtimeEventType(val eventName: String) {
    ConnectEvent("connectEvent"),
    SubscribeResponse("subscribeResponse"),
    UnSubscribeResponse("unSubscribeResponse"),
    SetUserCliResponse("setUserCliResponse"),
    TileUpdate("tileUpdate"),
    ConfigChanged("configChanged"),
    RegisterTileConfirmation("registerTileConfirmation"),
    UnregisterTileConfirmation("unregisterTileConfirmation"),
    PresenceEvent("presenceEvent"),
    CallHistoryEvent("callHistoryEvent"),
    CallMatchEvent("callMatchEvent"),
    CallEvent("callEvent"),
    CallerIdEvent("callerIdEvent")
}
