package com.example.calibredrive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LibraryIndex(
    val schemaVersion: String,
    val source: String,
    val generatedAt: String,
    val libraryRoot: String = "",
    val syncRoot: String? = null,
    val bookCount: Int,
    val books: List<BookEntry>,
)

@Serializable
data class BookEntry(
    val bookId: String,
    val title: String,
    val titleSort: String = title,
    val authors: List<String> = emptyList(),
    val authorSort: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    val tags: List<String> = emptyList(),
    val rating: Double? = null,
    val languages: List<String> = emptyList(),
    val publisher: String? = null,
    val pubdate: String? = null,
    val timestamp: String? = null,
    val identifiers: Map<String, String> = emptyMap(),
    val customColumns: Map<String, JsonElement> = emptyMap(),
    val relativeDir: String,
    val coverLocalPath: String? = null,
    val formatFiles: Map<String, String> = emptyMap(),
    val driveFiles: Map<String, DriveFileRef> = emptyMap(),
)

@Serializable
data class DriveFileRef(
    val id: String,
    val name: String,
    val webViewLink: String,
)

data class LibraryUiState(
    val loading: Boolean = false,
    val treeUri: String? = null,
    val index: LibraryIndex? = null,
    val searchQuery: String = "",
    val sortMode: BookSortMode = BookSortMode.Title,
    val selectedTags: Set<String> = emptySet(),
    val selectedEstados: Set<String> = emptySet(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val syncingBookId: String? = null,
    // true when cacheBookFormat failed because no Google account has been linked yet
    val pendingAccountPicker: Boolean = false,
)

enum class BookSortMode {
    Title,
    TitleSort,
    Author,
    Series,
    RatingDesc,
    PubDateDesc,
    TimestampDesc,
}
