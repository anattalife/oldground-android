package com.brandonthomas.oldground

import android.app.Application
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.map.TileJobs
import org.maplibre.android.MapLibre

class OldGroundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Keep a record of any crash so the app can show it (and you can send it) next time.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "last-crash.txt").writeText(
                    "OldGround ${packageManager.getPackageInfo(packageName, 0).versionName} " +
                        "on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n" +
                        "${java.util.Date()} thread ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, e)
        }
        MapLibre.getInstance(this)
        Repo.init(this)
        com.brandonthomas.oldground.map.EsriToken.refresh(this)
        com.brandonthomas.oldground.community.Community.init(this)
        // Finish any map tiles that were still being built when the app last closed.
        TileJobs.resumeAll(this)
    }

    companion object {
        lateinit var instance: OldGroundApp
            private set
    }
}
