package com.folio.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class ReaderTheme(val label: String) { LIGHT("Light"), SEPIA("Sepia"), DARK("Dark") }
enum class LayoutMode { GRID, LIST }
enum class DocSort(val label: String) { RECENT_ADDED("Recently added"), RECENT_OPENED("Recently opened"), TITLE("Title"), SIZE("Size"), TYPE("Type") }
enum class SearchEngine(val label: String, val template: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
}

data class UserPrefs(
    val theme: AppTheme = AppTheme.SYSTEM,
    val dynamicColor: Boolean = false,
    val readerTheme: ReaderTheme = ReaderTheme.LIGHT,
    val readerFontScale: Float = 1f,
    val keepScreenOn: Boolean = true,
    /** PDFs open as a book (page turns) rather than a continuous scroll. Remembered permanently. */
    val pdfBookMode: Boolean = true,
    val docLayout: LayoutMode = LayoutMode.GRID,
    val docSort: DocSort = DocSort.RECENT_ADDED,
    val bookmarkLayout: LayoutMode = LayoutMode.LIST,
    val browserJavaScript: Boolean = true,
    val browserSaveHistory: Boolean = true,
    val browserBlockThirdPartyCookies: Boolean = true,
    val browserClearOnExit: Boolean = false,
    val openLinksInApp: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "folio_prefs")

/** User preferences live in DataStore (typed, async, transactional) rather than a Room table. */
@Singleton
class PrefsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val readerTheme = stringPreferencesKey("reader_theme")
        val fontScale = floatPreferencesKey("reader_font_scale")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val pdfBook = booleanPreferencesKey("pdf_book_mode")
        val docLayout = stringPreferencesKey("doc_layout")
        val docSort = stringPreferencesKey("doc_sort")
        val bmLayout = stringPreferencesKey("bookmark_layout")
        val js = booleanPreferencesKey("browser_js")
        val history = booleanPreferencesKey("browser_history")
        val cookies = booleanPreferencesKey("browser_block_3p_cookies")
        val clearOnExit = booleanPreferencesKey("browser_clear_on_exit")
        val inApp = booleanPreferencesKey("open_links_in_app")
        val engine = stringPreferencesKey("search_engine")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    val prefs: Flow<UserPrefs> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            val d = UserPrefs()
            UserPrefs(
                theme = p.enum(K.theme, d.theme),
                dynamicColor = p[K.dynamic] ?: d.dynamicColor,
                readerTheme = p.enum(K.readerTheme, d.readerTheme),
                readerFontScale = (p[K.fontScale] ?: d.readerFontScale).coerceIn(0.6f, 2.5f),
                keepScreenOn = p[K.keepOn] ?: d.keepScreenOn,
                pdfBookMode = p[K.pdfBook] ?: d.pdfBookMode,
                docLayout = p.enum(K.docLayout, d.docLayout),
                docSort = p.enum(K.docSort, d.docSort),
                bookmarkLayout = p.enum(K.bmLayout, d.bookmarkLayout),
                browserJavaScript = p[K.js] ?: d.browserJavaScript,
                browserSaveHistory = p[K.history] ?: d.browserSaveHistory,
                browserBlockThirdPartyCookies = p[K.cookies] ?: d.browserBlockThirdPartyCookies,
                browserClearOnExit = p[K.clearOnExit] ?: d.browserClearOnExit,
                openLinksInApp = p[K.inApp] ?: d.openLinksInApp,
                searchEngine = p.enum(K.engine, d.searchEngine),
            )
        }

    suspend fun setTheme(v: AppTheme) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[K.dynamic] = v }
    suspend fun setReaderTheme(v: ReaderTheme) = context.dataStore.edit { it[K.readerTheme] = v.name }
    suspend fun setReaderFontScale(v: Float) = context.dataStore.edit { it[K.fontScale] = v.coerceIn(0.6f, 2.5f) }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[K.keepOn] = v }
    suspend fun setPdfBookMode(v: Boolean) = context.dataStore.edit { it[K.pdfBook] = v }
    suspend fun setDocLayout(v: LayoutMode) = context.dataStore.edit { it[K.docLayout] = v.name }
    suspend fun setDocSort(v: DocSort) = context.dataStore.edit { it[K.docSort] = v.name }
    suspend fun setBookmarkLayout(v: LayoutMode) = context.dataStore.edit { it[K.bmLayout] = v.name }
    suspend fun setBrowserJavaScript(v: Boolean) = context.dataStore.edit { it[K.js] = v }
    suspend fun setBrowserSaveHistory(v: Boolean) = context.dataStore.edit { it[K.history] = v }
    suspend fun setBlockThirdPartyCookies(v: Boolean) = context.dataStore.edit { it[K.cookies] = v }
    suspend fun setClearOnExit(v: Boolean) = context.dataStore.edit { it[K.clearOnExit] = v }
    suspend fun setOpenLinksInApp(v: Boolean) = context.dataStore.edit { it[K.inApp] = v }
    suspend fun setSearchEngine(v: SearchEngine) = context.dataStore.edit { it[K.engine] = v.name }
}
