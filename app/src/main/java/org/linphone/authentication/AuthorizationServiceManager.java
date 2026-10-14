package org.linphone.authentication;

import android.content.Context;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.browser.AnyBrowserMatcher;
import net.openid.appauth.connectivity.ConnectionBuilder;

import org.linphone.utils.Log;

public class AuthorizationServiceManager {

    // Held strongly: with a weak reference the manager could be collected, and a new one would
    // create a second AuthorizationService while the old one was never disposed
    private static AuthorizationServiceManager INSTANCE;

    private static final String TAG = "AuthorizationServiceManager";

    private final Context mContext;
    private final AuthConfiguration mConfiguration;

    //FIXME - this needs cleanup on destroy
    private AuthorizationService AuthorizationServiceInstance;

    @AnyThread
    public static synchronized AuthorizationServiceManager getInstance(@NonNull Context context) {
        if (INSTANCE == null) {
            INSTANCE = new AuthorizationServiceManager(context.getApplicationContext());
        }

        return INSTANCE;
    }

    private AuthorizationServiceManager(Context context) {
        mContext = context;
        mConfiguration = AuthConfiguration.getInstance(context);
    }

    // Synchronized so concurrent callers (e.g. several services building API clients at once) share
    // one instance, rather than each creating their own that clearAuthorizationServiceInstance never disposes
    public synchronized AuthorizationService getAuthorizationServiceInstance() {
        if (AuthorizationServiceInstance == null) {
            Log.Log.i("Creating authorization service");
            try {
                ConnectionBuilder connectionBuilder = null;

                if (mConfiguration != null) {
                    mConfiguration.readConfiguration();
                    connectionBuilder = mConfiguration.getConnectionBuilder();
                }

                if (connectionBuilder != null)
                {
                    var builder = new AppAuthConfiguration.Builder();
                    builder.setBrowserMatcher(AnyBrowserMatcher.INSTANCE);
                    builder.setConnectionBuilder(connectionBuilder);
                    
                    AuthorizationServiceInstance = new AuthorizationService(mContext, builder.build());
                }

            } catch (AuthConfiguration.InvalidConfigurationException e) {
                return null;
            }
        }
        return AuthorizationServiceInstance;
    }

    public synchronized void clearAuthorizationServiceInstance() {
        if (AuthorizationServiceInstance != null) {
            AuthorizationServiceInstance.dispose();
            AuthorizationServiceInstance = null;
        }
    }
}
