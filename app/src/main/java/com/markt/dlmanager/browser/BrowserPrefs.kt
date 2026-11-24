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

/** Browser settings, bookmarks, pop-up exceptions and open tabs. */
object BrowserPrefs {
    private lateinit var prefs: SharedPreferences

    /** Observable from Compose. */
    val bookmarks = mutableStateListOf<Bookmark>()

    /** Sites (e.g. "example.com") where pop-ups are allowed. Observable from Compose. */
    val popupSites = mutableStateListOf<String>()

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
        popupSites.clear()
        popupSites.addAll(prefs.getStringSet("popupSites", emptySet()).orEmpty().sorted())
    }

    var searchEngine: SearchEngine
        get() = runCatching { SearchEngine.valueOf(prefs.getString("search", null) ?: "") }
            .getOrDefault(SearchEngine.GOOGLE)
        set(v) = prefs.edit().putString("search", v.name).apply()

    /** Block pop-ups and new windows unless they turn out to be a file download. */
    var blockPopups: Boolean
        get() = prefs.getBoolean("blockPopups", true)
        set(v) = prefs.edit().putBoolean("blockPopups", v).apply()

    /** Block requests to known pop-up / redirect ad networks. */
    var blockAdHosts: Boolean
        get() = prefs.getBoolean("blockAdHosts", true)
        set(v) = prefs.edit().putBoolean("blockAdHosts", v).apply()

    // region Pop-up exceptions

    fun popupsAllowed(site: String): Boolean = site.isNotEmpty() && site in popupSites

    fun setPopupsAllowed(site: String, allowed: Boolean) {
        if (site.isEmpty()) return
        if (allowed && site !in popupSites) popupSites.add(site)
        if (!allowed) popupSites.remove(site)
        prefs.edit().putStringSet("popupSites", popupSites.toSet()).apply()
    }

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
