package com.markt.dlmanager.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

enum class SearchEngine(val label: String, val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s"),
}

data class Bookmark(val title: String, val url: String)

data class SavedTab(val url: String, val title: String)

/**
 * A per-site switch (e.g. "pop-ups allowed on example.com"), saved in prefs.
 * [sites] is for Compose; [contains] can also be called from WebView's background threads.
 */
class SiteList internal constructor(private val key: String) {
    val sites = mutableStateListOf<String>()
    @Volatile private var snapshot: Set<String> = emptySet()

    internal fun load(prefs: SharedPreferences) {
        sites.clear()
        sites.addAll(prefs.getStringSet(key, emptySet()).orEmpty().sorted())
        snapshot = sites.toSet()
    }

    operator fun contains(site: String): Boolean = site.isNotEmpty() && site in snapshot

    internal fun set(prefs: SharedPreferences, site: String, on: Boolean) {
        if (site.isEmpty()) return
        if (on && site !in sites) sites.add(site)
        if (!on) sites.remove(site)
        snapshot = sites.toSet()
        prefs.edit().putStringSet(key, snapshot).apply()
    }
}

/** Browser settings, bookmarks, pop-up exceptions and open tabs. */
object BrowserPrefs {
    private lateinit var prefs: SharedPreferences

    /** Observable from Compose. */
    val bookmarks = mutableStateListOf<Bookmark>()

    /** Sites (e.g. "example.com") where pop-ups are allowed. */
    val popupSites = SiteList("popupSites")

    /** Sites where ads are allowed (for sites that refuse to work with an ad blocker). */
    val adSites = SiteList("adSites")

    /** Sites where every tap that leads to another site asks first. */
    val guardSites = SiteList("guardSites")

    fun init(context: Context) {
        prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        bookmarks.clear()
        runCatching {
            val arr = JSONArray(prefs.getString("bookmarks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                bookmarks.add(Bookmark(o.optString("title"), o.getString("url")))
            }
        }
        popupSites.load(prefs)
        adSites.load(prefs)
        guardSites.load(prefs)
    }

    var searchEngine: SearchEngine
        get() = runCatching { SearchEngine.valueOf(prefs.getString("search", null) ?: "") }
            .getOrDefault(SearchEngine.GOOGLE)
        set(v) = prefs.edit().putString("search", v.name).apply()

    /** Block pop-ups and new windows unless they turn out to be a file download. */
    var blockPopups: Boolean
        get() = prefs.getBoolean("blockPopups", true)
        set(v) = prefs.edit().putBoolean("blockPopups", v).apply()

    /** Block ad networks, hide ad boxes and remove invisible layers that steal taps. */
    var blockAdHosts: Boolean
        get() = prefs.getBoolean("blockAdHosts", true)
        set(v) = prefs.edit().putBoolean("blockAdHosts", v).apply()

    // region Per-site switches

    fun popupsAllowed(site: String): Boolean = site in popupSites
    fun setPopupsAllowed(site: String, allowed: Boolean) = popupSites.set(prefs, site, allowed)

    /** True when ads should be blocked on this site. Safe to call from any thread. */
    fun blockAdsOn(site: String): Boolean = blockAdHosts && site !in adSites
    fun setAdsAllowed(site: String, allowed: Boolean) = adSites.set(prefs, site, allowed)

    fun alwaysAsk(site: String): Boolean = site in guardSites
    fun setAlwaysAsk(site: String, on: Boolean) = guardSites.set(prefs, site, on)

    // endregion

    // region Bookmarks

    fun isBookmarked(url: String): Boolean = bookmarks.any { it.url == url }

    fun toggleBookmark(title: String, url: String) {
        if (!url.startsWith("http")) return
        val existing = bookmarks.indexOfFirst { it.url == url }
        if (existing >= 0) bookmarks.removeAt(existing) else bookmarks.add(Bookmark(title.ifBlank { url }, url))
        saveBookmarks()
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        saveBookmarks()
    }

    private fun saveBookmarks() {
        val arr = JSONArray()
        bookmarks.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }

    // endregion

    // region Open tabs

    fun saveTabs(tabs: List<SavedTab>, current: Int) {
        val arr = JSONArray()
        tabs.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) }
        prefs.edit().putString("tabs", arr.toString()).putInt("currentTab", current).apply()
    }

    fun loadTabs(): Pair<List<SavedTab>, Int> {
        val list = runCatching {
            val arr = JSONArray(prefs.getString("tabs", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedTab(o.optString("url"), o.optString("title"))
            }
        }.getOrDefault(emptyList())
        return list to prefs.getInt("currentTab", 0)
    }

    // endregion
}
