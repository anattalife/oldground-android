package com.brandonthomas.oldground

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.brandonthomas.oldground.community.Community
import com.brandonthomas.oldground.location.LocationHub
import com.brandonthomas.oldground.ui.AppShell
import com.brandonthomas.oldground.ui.CrashReport
import com.brandonthomas.oldground.ui.OldGroundTheme

/** The four tabs along the bottom. */
enum class Tab { MAP, SITES, FINDS, COMMUNITY, OLD }

/**
 * Something to show on the Map tab. Every "show on map", "start a visit" or "open this old map"
 * becomes one of these instead of opening another copy of the map. [token] makes each request
 * distinct, so one-time actions (start recording, a notice) happen once.
 */
data class MapRequest(
    val pinId: String? = null,
    val siteId: String? = null,
    /** "new" to outline a new site, or a site's id to redraw its outline. */
    val draw: String? = null,
    val startVisit: Boolean = false,
    val notice: String? = null,
    val mode: String? = null,
    val debug: String? = null,
    val token: Long = System.nanoTime(),
)

sealed interface Screen {
    // Tab roots.
    data object SitesRoot : Screen
    data class FindsRoot(val sub: Int = 0) : Screen
    data object OldMapsRoot : Screen
    data class CommunityRoot(val debug: String? = null) : Screen
    // Pages inside a tab.
    data class Site(val id: String, val debug: String? = null) : Screen
    data class Visit(val siteId: String, val day: String) : Screen
    data class Align(val mapId: String) : Screen
    data object Settings : Screen
    data object Help : Screen
    /** Fill in and sign a permission slip for a site. */
    data class PermissionSlip(val siteId: String, val debug: String? = null) : Screen
    data class HelpTopic(val id: String) : Screen
    data class CommunityPost(val id: String, val debug: String? = null) : Screen
    /** A new post, optionally filled in from one of your finds. */
    data class Compose(val pinId: String? = null, val debug: String? = null) : Screen
    /** Sign in to the community. */
    data class JoinPage(val debug: String? = null) : Screen
    /**
     * Not a page: going here switches to the Map tab and shows what's asked
     * ([mapId] also switches the old map on top).
     */
    data class Hunt(
        val mapId: String?, val pinId: String? = null, val mode: String? = null, val debug: String? = null,
        val draw: String? = null, val siteId: String? = null, val startVisit: Boolean = false, val notice: String? = null,
        /** Keep the old map that's showing (instead of switching to [mapId], which may be null). */
        val keepOldMap: Boolean = mapId == null,
    ) : Screen
}

/** One-time actions already done (so coming back to a screen doesn't repeat them). */
object Once {
    private val done = HashSet<String>()
    @Synchronized fun first(key: String): Boolean = done.add(key)
}

class MainActivity : ComponentActivity() {
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationHub.restart(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!LocationHub.hasPermission(this)) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        // The automated screenshots open a particular tab, page or map view.
        val i = intent
        val mapExtra = i?.getStringExtra("openMap")?.takeIf { it != "none" }
        val debug = i?.getStringExtra("debug")
        val start: Pair<Tab, List<Screen>>? = when (i?.getStringExtra("screen")) {
            "align" -> mapExtra?.let { Tab.OLD to listOf(Screen.Align(it)) }
            "sites" -> Tab.SITES to emptyList()
            "finds" -> Tab.FINDS to listOf(Screen.FindsRoot(when (debug) { "pins" -> 1; else -> 0 }))
            "old" -> Tab.OLD to emptyList()
            "community" -> Tab.COMMUNITY to listOf(Screen.CommunityRoot(debug))
            "post" -> Tab.COMMUNITY to listOf(Screen.CommunityRoot(), Screen.CommunityPost(i.getStringExtra("post") ?: "d1", debug))
            "compose" -> Tab.COMMUNITY to listOf(Screen.CommunityRoot(), Screen.Compose(debug = debug))
            "join" -> Tab.COMMUNITY to listOf(Screen.CommunityRoot(), Screen.JoinPage(debug))
            "settings" -> Tab.OLD to listOf(Screen.Settings)
            "slip" -> i.getStringExtra("site")?.let { Tab.SITES to listOf(Screen.Site(it), Screen.PermissionSlip(it, debug)) }
            "help" -> Tab.OLD to listOfNotNull(Screen.Settings, Screen.Help, i.getStringExtra("topic")?.let { Screen.HelpTopic(it) })
            "site" -> i.getStringExtra("site")?.let { Tab.SITES to listOf(Screen.Site(it, debug)) }
            "visit" -> {
                val site = i.getStringExtra("site")
                val day = i.getStringExtra("day")
                if (site != null && day != null) Tab.SITES to listOf(Screen.Site(site), Screen.Visit(site, day)) else null
            }
            else -> null
        }
        // Screenshots of the community use made-up posts, never the real server.
        if (i?.getStringExtra("community") == "demo") Community.startDemo()
        val startMap = if (i?.hasExtra("openMap") == true && start == null) {
            Screen.Hunt(
                mapExtra, i.getStringExtra("pin"), i.getStringExtra("mode"), debug,
                draw = i.getStringExtra("draw"), siteId = i.getStringExtra("site"), startVisit = i.getStringExtra("startVisit") == "1", keepOldMap = false,
            )
        } else null
        setContent {
            OldGroundTheme {
                AppShell(start, startMap)
                CrashReport()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocationHub.acquire(this)
    }

    override fun onPause() {
        LocationHub.release()
        super.onPause()
    }
}
