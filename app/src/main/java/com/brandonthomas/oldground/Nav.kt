package com.brandonthomas.oldground

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.brandonthomas.oldground.data.Repo

/**
 * Where you are in the app: the selected tab, each tab's own pages, the old map on the
 * Map tab, and the latest thing asked to be shown there. There is only ever one map.
 */
class ShellState(startTab: Tab, oldMap: String?) {
    var tab by mutableStateOf(startTab)
        private set
    val stacks: Map<Tab, SnapshotStateList<Screen>> = mapOf(
        Tab.MAP to mutableStateListOf(),
        Tab.SITES to mutableStateListOf(Screen.SitesRoot),
        Tab.FINDS to mutableStateListOf(Screen.FindsRoot()),
        Tab.COMMUNITY to mutableStateListOf(Screen.CommunityRoot()),
        Tab.OLD to mutableStateListOf(Screen.OldMapsRoot),
    )
    /** The old map on top on the Map tab (null: today's maps only). */
    var oldMapId by mutableStateOf(oldMap)
        private set
    var request by mutableStateOf<MapRequest?>(null)
        private set

    val canGoBack get() = tab != Tab.MAP || stacks.getValue(tab).size > 1

    fun select(t: Tab) {
        if (t == tab) {
            // Tapping the tab you're on goes back to its first page.
            val st = stacks.getValue(t)
            while (st.size > 1) st.removeAt(st.lastIndex)
        } else tab = t
    }

    private fun openIn(t: Tab, reset: Boolean, vararg pages: Screen) {
        val st = stacks.getValue(t)
        if (reset || tab != t) while (st.size > 1) st.removeAt(st.lastIndex)
        st.addAll(pages)
        tab = t
    }

    fun go(s: Screen) {
        when (s) {
            is Screen.Hunt -> showOnMap(s)
            Screen.SitesRoot -> openIn(Tab.SITES, true)
            Screen.OldMapsRoot -> openIn(Tab.OLD, true)
            is Screen.FindsRoot -> {
                val st = stacks.getValue(Tab.FINDS)
                st.clear(); st.add(s); tab = Tab.FINDS
            }
            is Screen.Site, is Screen.Visit, is Screen.PermissionSlip -> openIn(Tab.SITES, false, s)
            is Screen.Align -> openIn(Tab.OLD, false, s)
            is Screen.CommunityRoot -> {
                val st = stacks.getValue(Tab.COMMUNITY)
                st.clear(); st.add(s); tab = Tab.COMMUNITY
            }
            is Screen.CommunityPost, is Screen.Compose, is Screen.JoinPage -> openIn(Tab.COMMUNITY, false, s)
            Screen.Help, is Screen.HelpTopic -> {
                if (tab == Tab.MAP) tab = Tab.OLD
                stacks.getValue(tab).add(s)
            }
            Screen.Settings -> {
                if (tab == Tab.MAP) tab = Tab.OLD
                stacks.getValue(tab).add(s)
            }
        }
    }

    /** Back: the previous page on this tab, else the Map tab. False when there's nowhere to go. */
    fun back(): Boolean {
        val st = stacks.getValue(tab)
        return when {
            st.size > 1 -> { st.removeAt(st.lastIndex); true }
            tab != Tab.MAP -> { tab = Tab.MAP; true }
            else -> false
        }
    }

    fun replace(s: Screen) {
        val st = stacks.getValue(tab)
        if (s is Screen.Hunt || st.isEmpty()) {
            if (st.size > 1) st.removeAt(st.lastIndex)
            go(s)
        } else st[st.lastIndex] = s
    }

    fun setOldMap(id: String?) {
        oldMapId = id
        Repo.updateSettings { it.copy(lastOldMap = id) }
    }

    private fun showOnMap(h: Screen.Hunt) {
        if (!h.keepOldMap && h.mapId != oldMapId) setOldMap(h.mapId)
        request = MapRequest(h.pinId, h.siteId, h.draw, h.startVisit, h.notice, h.mode, h.debug)
        tab = Tab.MAP
    }
}

class Nav(val shell: ShellState) {
    fun go(s: Screen) = shell.go(s)
    fun back() { shell.back() }
    fun replace(s: Screen) = shell.replace(s)
    fun tab(t: Tab) = shell.select(t)
}
