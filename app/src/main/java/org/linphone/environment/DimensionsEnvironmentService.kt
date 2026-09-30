package org.linphone.environment

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.AnyThread
import com.google.gson.Gson
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONException
import org.linphone.R
import org.linphone.models.DimensionsEnvironment
import org.linphone.models.EnvironmentOverride
import org.linphone.utils.Log

class DimensionsEnvironmentService(context: Context) {
    companion object {
        private val INSTANCE_REF: AtomicReference<WeakReference<DimensionsEnvironmentService>> = AtomicReference(
            WeakReference(null)
        )
        private const val STORE_NAME: String = "environment"
        private const val KEY_STATE: String = "environment-state"
        private const val KEY_DEV_MODE: String = "dev-mode"

        @AnyThread
        fun getInstance(context: Context): DimensionsEnvironmentService {
            var service = INSTANCE_REF.get().get()
            if (service == null) {
                service = DimensionsEnvironmentService(context.applicationContext)
                INSTANCE_REF.set(WeakReference(service))
            }

            return service
        }
    }

    private var mPrefs: SharedPreferences = context.getSharedPreferences(
        STORE_NAME,
        Context.MODE_PRIVATE
    )
    private var mResources = context.resources
    private var mPrefsLock: ReentrantLock = ReentrantLock()

    @Volatile private var isListInitialised: Boolean = false
    private var isDevModeEnabled: Boolean = false

    // The environment a brand marks isDefault in environment_overrides.json, if any.
    private var brandDefaultId: String? = null

    private val currentEnvironmentSubject = BehaviorSubject.create<DimensionsEnvironment>()
    val currentEnvironmentObservable = currentEnvironmentSubject.map { x -> x }
        .replay(1)
        .autoConnect()

    init {
        isDevModeEnabled = mPrefs.getBoolean(KEY_DEV_MODE, false)
    }

    @AnyThread
    fun getCurrentEnvironment(): DimensionsEnvironment? {
        if (currentEnvironmentSubject.value != null) {
            return currentEnvironmentSubject.value
        }

        val dimensionsEnvironment: DimensionsEnvironment? = readEnvironment()
        if (dimensionsEnvironment != null) {
            currentEnvironmentSubject.onNext(dimensionsEnvironment)
            return dimensionsEnvironment
        }

        return currentEnvironmentSubject.value
    }

    @AnyThread
    fun setCurrentEnvironment(dimensionsEnvironment: DimensionsEnvironment): DimensionsEnvironment {
        writeEnvironment(dimensionsEnvironment)
        currentEnvironmentSubject.onNext(dimensionsEnvironment)
        return dimensionsEnvironment
    }

    @AnyThread
    fun getEnvironmentList(): List<DimensionsEnvironment> {
        addEnvironmentOverrides()

        return environments.filter { e -> !e.isHidden || isDevModeEnabled }
    }

    // The environment list loads on a background thread while LoginActivity reads the current
    // environment on the main thread, so both paths call this and it only runs once.
    @Synchronized
    private fun addEnvironmentOverrides() {
        if (isListInitialised) return

        // Read any environment overrides for the current build variant:
        var overrideList: Array<EnvironmentOverride>

        mResources.openRawResource(R.raw.environment_overrides)
            .bufferedReader().use {
                val jsonStr = it.readText()
                overrideList = Gson().fromJson(jsonStr, Array<EnvironmentOverride>::class.java)
            }

        var defaultId: String? = null

        // For each override, take any non-null properties
        for (override in overrideList) {
            val env = environments.firstOrNull { e -> e.id == override.id }
            if (env != null) {
                env.name = override.name ?: env.name
                env.defaultTenantId = override.defaultTenantId ?: env.defaultTenantId
                env.documentationUri = override.documentationUri ?: env.documentationUri
                if (override.isDefault) defaultId = override.id
            }
        }

        // If necessary, override the default environment
        if (defaultId != null) {
            environments.forEach { env -> env.isDefault = env.id == defaultId }
        }

        brandDefaultId = defaultId
        isListInitialised = true
    }

    @AnyThread
    fun readEnvironment(): DimensionsEnvironment? {
        mPrefsLock.lock()
        try {
            val currentEnvironment = mPrefs.getString(KEY_STATE, null)

            if (currentEnvironment.isNullOrBlank()) {
                return getDefaultEnvironment()
            }

            try {
                return DimensionsEnvironment.jsonDeserialize(currentEnvironment)
            } catch (ex: JSONException) {
                Log.w("Failed to deserialize stored auth state - discarding")
                return null
            }
        } finally {
            mPrefsLock.unlock()
        }
    }

    @AnyThread
    private fun writeEnvironment(newEnvironment: DimensionsEnvironment?) {
        mPrefsLock.lock()
        try {
            val editor = mPrefs.edit()
            if (newEnvironment == null) {
                editor.remove(KEY_STATE)
            } else {
                editor.putString(KEY_STATE, DimensionsEnvironment.jsonSerialize(newEnvironment))
            }

            check(editor.commit()) { "Failed to write state to shared prefs" }
        } finally {
            mPrefsLock.unlock()
        }
    }

    private fun getDefaultEnvironment(): DimensionsEnvironment? {
        addEnvironmentOverrides()

        return resolveDefaultEnvironment(
            environments,
            brandDefaultId,
            Locale.getDefault().toLanguageTag()
        )
    }

    fun toggleDevMode() {
        isDevModeEnabled = !isDevModeEnabled
        mPrefsLock.lock()
        try {
            val editor = mPrefs.edit()
            editor.putBoolean(KEY_DEV_MODE, isDevModeEnabled)
            check(editor.commit()) { "Failed to write dev mode setting" }
        } finally {
            mPrefsLock.unlock()
        }
    }
}

/**
 * Picks the environment to use before the user has chosen one. A brand that names a default in
 * environment_overrides.json always starts there. Otherwise the first environment matching the
 * device locale wins, falling back to the environment marked isDefault.
 */
internal fun resolveDefaultEnvironment(
    environments: List<DimensionsEnvironment>,
    brandDefaultId: String?,
    localeTag: String
): DimensionsEnvironment? {
    if (brandDefaultId != null) {
        environments.firstOrNull { x -> x.id == brandDefaultId }?.let { return it }
    }

    return environments.firstOrNull { x -> x.locales.contains(localeTag) }
        ?: environments.firstOrNull { x -> x.isDefault }
}
