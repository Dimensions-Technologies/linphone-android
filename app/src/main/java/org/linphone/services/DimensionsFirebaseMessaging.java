package org.linphone.services;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.linphone.core.PushWakeLock;
import org.linphone.core.tools.firebase.FirebaseMessaging;

public class DimensionsFirebaseMessaging extends FirebaseMessaging {
    public DimensionsFirebaseMessaging() {
    }

    @Override
    public void onNewToken(final String token) {
        super.onNewToken(token);

        Context context = getApplicationContext();
        PushTokenService pushTokenService = PushTokenService.Companion.getInstance(context);
        pushTokenService.updateToken(token);
        pushTokenService.updateVoipToken(token);

        UserService.Companion.getInstance(context).createUserSession();
    }

    @Override
    public void handleIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (PushPayloadFilter.INSTANCE.shouldHandle(key -> extras == null ? null : extras.getString(key))) {
            if (PushPayloadFilter.INSTANCE.isMessage(intent.getAction())) {
                // Taken before the SDK sees the push, while Firebase still holds its own lock
                PushWakeLock.Companion.get(getApplicationContext()).acquire();
            }
            super.handleIntent(intent);
        }
    }
}
