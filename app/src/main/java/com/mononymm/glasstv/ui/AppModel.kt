package com.mononymm.glasstv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mononymm.glasstv.plex.Item
import com.mononymm.glasstv.plex.Plex
import com.mononymm.glasstv.plex.Prefs
import com.mononymm.glasstv.plex.Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

sealed interface Screen {
    data object SignIn : Screen
    data object Servers : Screen
    data object Home : Screen
    data class Library(val section: Section) : Screen
    data class Detail(val item: Item) : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Player(val item: Item, val fromStart: Boolean) : Screen
}

class AppModel(ctx: Context, version: String) {
    val prefs = Prefs(ctx.applicationContext)
    val plex = Plex(prefs, version)
    val version = version

    /** Outlives screens and menus (marking watched etc. finishes after a menu closes). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val stack = mutableStateListOf<Screen>()
    var sections by mutableStateOf<List<Section>>(emptyList())
    var sectionOrder by mutableStateOf(prefs.sectionOrder)
    var homeOrder by mutableStateOf(prefs.homeOrder)
    var hiddenSections by mutableStateOf(prefs.hiddenSections)

    /** Libraries shown in the side bar (and on Home), in the user's order. */
    val pinnedSections: List<Section> get() = orderedSections.filter { it.key !in hiddenSections }

    fun setPinned(key: String, pinned: Boolean) {
        hiddenSections = if (pinned) hiddenSections - key else hiddenSections + key
        prefs.hiddenSections = hiddenSections
    }

    /** Libraries in the user's order; any not yet ordered keep the server's order after them. */
    val orderedSections: List<Section>
        get() = sections.sortedBy { s -> sectionOrder.indexOf(s.key).let { if (it < 0) Int.MAX_VALUE else it } }

    /** Moves [key] past its pinned neighbour (hidden libraries keep their places). */
    fun moveSection(key: String, dir: Int) {
        val keys = orderedSections.map { it.key }.toMutableList()
        val pinned = pinnedSections.map { it.key }
        val p = pinned.indexOf(key)
        val other = pinned.getOrNull(p + dir) ?: return
        if (p < 0) return
        val i = keys.indexOf(key)
        val j = keys.indexOf(other)
        java.util.Collections.swap(keys, i, j)
        sectionOrder = keys
        prefs.sectionOrder = keys
    }

    /** [current] is the row ids as shown now. */
    fun moveHomeRow(current: List<String>, id: String, dir: Int) {
        val keys = current.toMutableList()
        val i = keys.indexOf(id)
        val j = i + dir
        if (i < 0 || j !in keys.indices) return
        java.util.Collections.swap(keys, i, j)
        homeOrder = keys
        prefs.homeOrder = keys
    }

    /** Bumped after something changes watched state so screens reload. */
    var refresh by mutableStateOf(0)

    init {
        com.mononymm.glasstv.ui.Skin.amoled = prefs.skin != "classic"
        stack += when {
            prefs.accountToken == null -> Screen.SignIn
            prefs.serverUri == null -> Screen.Servers
            else -> Screen.Home
        }
    }

    val current: Screen get() = stack.last()

    fun go(s: Screen) {
        stack += s
    }

    fun replaceAll(s: Screen) {
        stack.clear()
        stack += s
    }

    /** false when there's nowhere to go back to (let the system close the app). */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun changed() {
        cache.clear()
        refresh++
    }

    /** Last loaded data per screen, so going back shows it instantly while it refreshes. */
    val cache = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> cached(key: String): T? = cache[key] as T?

    fun signOut() {
        prefs.signOut()
        sections = emptyList()
        replaceAll(Screen.SignIn)
    }
}
