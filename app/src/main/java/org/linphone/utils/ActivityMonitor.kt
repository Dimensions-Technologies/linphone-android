/*
 * Copyright (c) 2010-2021 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.utils

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.service.AndroidDispatcher

class ActivityMonitor : ActivityLifecycleCallbacks {
    // Only activities seen starting are counted when they stop
    private val visibleActivities = HashSet<Activity>()

    private val foregroundTracker = ForegroundTracker(
        { delayMs, task ->
            var isCanceled = false
            AndroidDispatcher.dispatchOnUIThreadAfter({ if (!isCanceled) task() }, delayMs)
            ForegroundTracker.Cancellable { isCanceled = true }
        },
        onForeground = { coreContext.onForeground() },
        onBackground = { coreContext.onBackground() }
    )

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    @Synchronized
    override fun onActivityStarted(activity: Activity) {
        if (visibleActivities.add(activity)) foregroundTracker.onActivityVisible()
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    @Synchronized
    override fun onActivityStopped(activity: Activity) {
        if (visibleActivities.remove(activity)) foregroundTracker.onActivityHidden()
    }

    @Synchronized
    override fun onActivityDestroyed(activity: Activity) {
        // Stopped is normally called first, this only covers it being skipped
        if (visibleActivities.remove(activity)) foregroundTracker.onActivityHidden()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
