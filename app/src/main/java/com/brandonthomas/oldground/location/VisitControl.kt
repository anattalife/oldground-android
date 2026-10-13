package com.brandonthomas.oldground.location

import android.content.Context
import com.brandonthomas.oldground.data.Repo

/**
 * Visits drive path recording: Start a visit begins one (and records your path when that's
 * switched on in Settings); End visit stops both.
 */
object VisitControl {
    fun start(context: Context, siteId: String) {
        Repo.beginVisit(siteId)
        if (Repo.current.settings.recordPath && Repo.current.recording == null) TrackService.start(context)
    }

    fun end(context: Context) {
        Repo.finishVisit()
        if (Repo.current.recording != null) TrackService.stop(context)
    }
}
