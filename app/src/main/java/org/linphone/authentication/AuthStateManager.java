/*
 * Copyright 2017 The AppAuth for Android Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.linphone.authentication;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.EndSessionRequest;
import net.openid.appauth.RegistrationResponse;
import net.openid.appauth.TokenResponse;
import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.linphone.activities.main.MainActivity;
import org.linphone.activities.main.LoginActivity;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;

/**
 * An example persistence mechanism for an {@link AuthState} instance.
 * This stores the instance in a shared preferences file, and provides thread-safe access and
 * mutation.
 */
public class AuthStateManager {

    private static final AtomicReference<WeakReference<AuthStateManager>> INSTANCE_REF =
            new AtomicReference<>(new WeakReference<>(null));

    private static final String TAG = "AuthStateManager";

    private static final String STORE_NAME = "AuthState";
    private static final String KEY_STATE = "state";

    private final SharedPreferences mPrefs;
    private final ReentrantLock mPrefsLock;
    private final AtomicReference<AuthState> mCurrentAuthState;

    private final BehaviorSubject<String> userNameSubject = BehaviorSubject.createDefault("...");
    public final Observable<String> userName = userNameSubject.map(x -> "User: " + x);

    @AnyThread
    public static AuthStateManager getInstance(@NonNull Context context) {
        AuthStateManager manager = INSTANCE_REF.get().get();
        if (manager == null) {
            manager = new AuthStateManager(context.getApplicationContext());
            INSTANCE_REF.set(new WeakReference<>(manager));
        }

        return manager;
    }

    private AuthStateManager(Context context) {
        mPrefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE);
        mPrefsLock = new ReentrantLock();
        mCurrentAuthState = new AtomicReference<>();
    }

    @AnyThread
    @NonNull
    public AuthState getCurrent() {
        if (mCurrentAuthState.get() != null) {
            return mCurrentAuthState.get();
        }

        AuthState state = readState();
        updateObservable(state);

        if (mCurrentAuthState.compareAndSet(null, state)) {
            return state;
        } else {
            return mCurrentAuthState.get();
        }
    }

    @AnyThread
    @NonNull
    public AuthState replace(@NonNull AuthState state) {
        writeState(state);
        mCurrentAuthState.set(state);
        return state;
    }

    @AnyThread
    @NonNull
    public AuthState updateAfterAuthorization(
            @Nullable AuthorizationResponse response,
            @Nullable AuthorizationException ex) {
        AuthState current = getCurrent();
        current.update(response, ex);
        if (response != null) {
            var str = "IDT:" + response.idToken + " ACT:" + response.accessToken + " AC:" + response.authorizationCode;
            userNameSubject.onNext(str);
        }
        //updateObservable(current);
        return replace(current);
    }

    @AnyThread
    @NonNull
    public AuthState updateAfterTokenResponse(
            @Nullable TokenResponse response,
            @Nullable AuthorizationException ex) {
        AuthState current = getCurrent();
        current.update(response, ex);
        updateObservable(current);
        return replace(current);
    }

    @AnyThread
    @NonNull
    public AuthState updateAfterRegistration(
            RegistrationResponse response,
            AuthorizationException ex) {
        AuthState current = getCurrent();
        updateObservable(current);
        if (ex != null) {
            return current;
        }

        current.update(response);
        return replace(current);
    }

    @AnyThread
    @NonNull
    private AuthState readState() {
        mPrefsLock.lock();
        try {
            String currentState = mPrefs.getString(KEY_STATE, null);
            if (currentState == null) {
                return new AuthState();
            }

            try {
                return AuthState.jsonDeserialize(currentState);
            } catch (JSONException ex) {
                Log.w(TAG, "Failed to deserialize stored auth state - discarding");
                return new AuthState();
            }
        } finally {
            mPrefsLock.unlock();
        }
    }

    @AnyThread
    private void writeState(@Nullable AuthState state) {
        mPrefsLock.lock();
        try {
            SharedPreferences.Editor editor = mPrefs.edit();
            if (state == null) {
                editor.remove(KEY_STATE);
            } else {
                editor.putString(KEY_STATE, state.jsonSerializeString());
            }

            if (!editor.commit()) {
                throw new IllegalStateException("Failed to write state to shared prefs");
            }
        } finally {
            updateObservable(state);
            mPrefsLock.unlock();
        }
    }

    public void logout(Context context) {
        final var state = getCurrent();
        final var authService = new AuthorizationService(context);
        final var config = AuthConfiguration.getInstance(context);
        final var authConfig = state.getAuthorizationServiceConfiguration();
        if (authConfig == null) {
            // TODO: handle this
            return;
        }

        EndSessionRequest endSessionRequest =
                new EndSessionRequest.Builder(authConfig)
                        .setIdTokenHint(state.getIdToken())
                        .setPostLogoutRedirectUri(config.getEndSessionRedirectUri())
                        .build();

        var endSessionIntent = new Intent(context, LoginActivity.class);// authService.getEndSessionRequestIntent(endSessionRequest);

        authService.performEndSessionRequest(
                endSessionRequest,
                PendingIntent.getActivity(context, 0, endSessionIntent, PendingIntent.FLAG_IMMUTABLE),
                PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }


    private void updateObservable(@Nullable AuthState state) {
        var token = state == null ? "<null>" : state.getIdToken();

        Log.d("AuthStateManager", "Token: " + token);
        if (token == null) token = "No token";

        if (state != null) {
            token += "\nAuthorised:" + state.isAuthorized();
            token += "\nAccess: " + state.getAccessToken();
            token += "\nLTR: " + state.getLastTokenResponse();
        }

        userNameSubject.onNext(token);
    }
}
