package com.example.calibredrive

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class LibraryStore(
    private val context: Context,
    private val parser: IndexParser = IndexParser(),
) {
    private val logTag = "CalibreDrive"

    private data class IndexedDocument(
        val document: DocumentFile,
        val basePath: String,
    )

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // Persistent SAF document ID cache: survives cold-cache AND the Drive SAF 50-item page limit.
    // Keyed by "dir:<bookId>", "fmt:<bookId>/<format>", "cover:<bookId>" → SAF document ID string.
    private val docIdPrefs = context.getSharedPreferences("calibre_doc_ids", Context.MODE_PRIVATE)
    // Persistent Drive REST API file ID cache: keyed by "<bookId>_<format>" → Drive file ID.
    // Populated on first successful Drive API search (when driveFiles index entry is empty).
    private val driveApiIdPrefs = context.getSharedPreferences("calibre_drive_api_ids", Context.MODE_PRIVATE)
    private val cachedIndexFile = File(context.filesDir, "library/index.json")
    private val offlineRoot = File(context.filesDir, "offline-cache")

    // In-memory caches: populated at startup from persisted doc IDs or warmup traversal
    private val coverDocCache = ConcurrentHashMap<String, DocumentFile>()   // bookId → cover
    private val formatDocCache = ConcurrentHashMap<String, DocumentFile>()  // "${bookId}/${format}" → file
    private val bookDirDocCache = ConcurrentHashMap<String, DocumentFile>() // bookId → directory
    // Cached Drive SAF root IDs (authority → list of rootIds) for search fallback
    private val cachedRootIds = ConcurrentHashMap<String, List<String>>()

    init {
        cachedIndexFile.parentFile?.mkdirs()
        offlineRoot.mkdirs()
    }

    fun loadSavedTreeUri(): Uri? {
        val raw = preferences.getString(KEY_TREE_URI, null) ?: return null
        return Uri.parse(raw)
    }

    fun saveTreeUri(uri: Uri) {
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    /** True when the user has already linked a Google account via the account picker. */
    fun hasSavedGoogleAccount(): Boolean = preferences.contains(KEY_GOOGLE_ACCOUNT_NAME)

    /** Stores the Google account name chosen by the user so [fetchDriveToken] can use it. */
    fun saveGoogleAccountName(name: String) {
        Log.d(logTag, "saveGoogleAccountName: $name")
        preferences.edit().putString(KEY_GOOGLE_ACCOUNT_NAME, name).apply()
    }

    fun hasResolvedIndexBasePath(): Boolean {
        return preferences.contains(KEY_INDEX_BASE_PATH)
    }

    // --------------- Persistent SAF doc-ID helpers ---------------

    private fun persistDocId(key: String, doc: DocumentFile) {
        val docId = try { DocumentsContract.getDocumentId(doc.uri) } catch (_: Exception) { return }
        docIdPrefs.edit().putString(key, docId).apply()
    }

    private fun loadPersistedDoc(treeUri: Uri, key: String): DocumentFile? {
        val docId = docIdPrefs.getString(key, null) ?: return null
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            DocumentFile.fromSingleUri(context, uri)
        } catch (_: Exception) { null }
    }

    private fun clearPersistedDocIds() {
        docIdPrefs.edit().clear().apply()
    }

    suspend fun importIndex(treeUri: Uri): LibraryIndex = withContext(Dispatchers.IO) {
        val indexDocument = findIndexDocument(treeUri)
            ?: error("No se encontró index.json dentro de la carpeta elegida")
        val storedBasePath = indexDocument.basePath.ifBlank { ROOT_INDEX_BASE_PATH }
        preferences.edit().putString(KEY_INDEX_BASE_PATH, storedBasePath).apply()
        coverDocCache.clear()
        formatDocCache.clear()
        bookDirDocCache.clear()
        clearPersistedDocIds()
        context.contentResolver.openInputStream(indexDocument.document.uri).use { input ->
            requireNotNull(input) { "No se pudo abrir index.json" }
            cachedIndexFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val raw = cachedIndexFile.readText()
        parser.parse(raw)
    }

    fun loadCachedIndex(): LibraryIndex? {
        if (!cachedIndexFile.exists()) {
            return null
        }
        return runCatching { parser.parse(cachedIndexFile.readText()) }.getOrNull()
    }

    suspend fun cacheBookFormat(
        treeUri: Uri,
        index: LibraryIndex,
        book: BookEntry,
        format: String,
    ): File? = withContext(Dispatchers.IO) {
        Log.d(logTag, "cacheBookFormat: start book=${book.bookId} format=$format formatKeys=${book.formatFiles.keys}")
        val sourcePath = book.formatFiles[format] ?: run {
            Log.w(logTag, "cacheBookFormat: no entry for format=$format in keys=${book.formatFiles.keys}")
            return@withContext null
        }
        val relativePath = relativePathFromIndex(index.libraryRoot, sourcePath) ?: run {
            Log.w(logTag, "cacheBookFormat: relativePathFromIndex returned null sourcePath=$sourcePath root=${index.libraryRoot}")
            return@withContext null
        }
        val targetFile = offlineFile(book.bookId, format, relativePath)

        // --- Try SAF first (works for ~50 books that are in Drive's first listing page) ---
        val document = resolveBookFormatDocument(treeUri, index, book, format)
        if (document != null) {
            Log.d(logTag, "cacheBookFormat: SAF found name=${document.name} uri=${document.uri}")
            targetFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(document.uri).use { input ->
                requireNotNull(input) { "openInputStream returned null for ${document.uri}" }
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(logTag, "cacheBookFormat: SAF done size=${targetFile.length()}")
            return@withContext targetFile
        }

        // --- Fallback: Drive REST API (works for ALL books regardless of SAF page limit) ---
        // Step 1: obtain an OAuth token.
        val token = fetchDriveToken() ?: run {
            Log.w(logTag, "cacheBookFormat: Drive API auth token unavailable (consent may be pending — try again after granting access)")
            return@withContext null
        }

        // Step 2: resolve the Drive file ID.
        //   a) Prefer the ID from the index (populated when --upload-files was used).
        //   b) Fall back to a persistent cache from a previous successful search.
        //   c) As last resort, search Drive by the exact epub/pdf filename.
        val driveRef = book.driveFiles[format] ?: book.driveFiles[format.uppercase()]
        val cacheKey = "${book.bookId}_${format.lowercase()}"
        val driveFileId: String? = when {
            driveRef != null -> {
                Log.d(logTag, "cacheBookFormat: Drive fileId from index for book=${book.bookId}")
                driveRef.id
            }
            driveApiIdPrefs.contains(cacheKey) -> {
                val id = driveApiIdPrefs.getString(cacheKey, null)!!
                Log.d(logTag, "cacheBookFormat: Drive fileId from cache key=$cacheKey id=$id")
                id
            }
            else -> {
                val fileName = sourcePath.substringAfterLast('/').substringAfterLast('\\')
                Log.d(logTag, "cacheBookFormat: searching Drive by filename='$fileName'")
                val foundId = searchDriveFileByName(fileName, token)
                if (foundId != null) {
                    driveApiIdPrefs.edit().putString(cacheKey, foundId).apply()
                    Log.d(logTag, "cacheBookFormat: Drive search found and cached fileId=$foundId")
                } else {
                    Log.w(logTag, "cacheBookFormat: Drive search returned no results for '$fileName'")
                }
                foundId
            }
        }

        if (driveFileId == null) {
            Log.w(logTag, "cacheBookFormat: no Drive file ID available for book=${book.bookId} format=$format")
            return@withContext null
        }

        // Step 3: download.
        targetFile.parentFile?.mkdirs()
        val ok = downloadDriveFile(driveFileId, token, targetFile)
        if (ok) {
            Log.d(logTag, "cacheBookFormat: Drive REST API done size=${targetFile.length()}")
            targetFile
        } else {
            targetFile.delete()
            null
        }
    }

    fun cachedBookFile(bookId: String, format: String): File? {
        val directory = File(offlineRoot, bookId)
        val match = directory.listFiles()?.firstOrNull { file ->
            file.name.startsWith("${format}.") || file.name == format
        }
        return match?.takeIf { it.exists() }
    }

    fun cachedCoverFile(book: BookEntry): File? {
        val coverPath = book.coverLocalPath ?: return null
        val directory = File(offlineRoot, book.bookId)
        val relativePath = coverPath.substringAfterLast('/').substringAfterLast('\\')
        return File(directory, relativePath).takeIf { it.exists() }
    }

    /**
     * Looks up a single child document by display name.
     * First queries the children listing (Drive returns only the first ~50 items).
     * If not found, falls back to Drive's server-side search (not subject to the 50-item limit).
     * Search results are not filtered to this specific parent, so exact name matching is relied
     * upon; book directory names are numeric Calibre IDs making collisions extremely unlikely.
     */
    private fun findChildDocumentByName(treeUri: Uri, parentDoc: DocumentFile, name: String): DocumentFile? {
        // --- primary: children listing ---
        try {
            val parentId = DocumentsContract.getDocumentId(parentDoc.uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == name) {
                        val docId = cursor.getString(0) ?: return@use
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        return DocumentFile.fromSingleUri(context, docUri)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(logTag, "findChildByName listing failed parent=${parentDoc.uri} name=$name: $e")
        }

        // --- fallback: Drive server-side search (bypasses the 50-item listing limit) ---
        val authority = treeUri.authority ?: return null
        return driveSearchFallback(treeUri, authority, name)
    }

    /**
     * Searches Drive SAF for a document with exactly [name] using [buildSearchDocumentsUri].
     * Drive's search is server-side and is NOT subject to the 50-item listing page limit.
     * Root IDs are derived from the tree document ID (format "acc=N;doc=...") to avoid
     * querying the roots URI which requires additional permissions.
     */
    private fun driveSearchFallback(treeUri: Uri, authority: String, name: String): DocumentFile? {
        val rootIds = cachedRootIds.getOrPut(authority) {
            // Drive SAF root IDs follow "acc=N"; extract the account prefix from the tree doc ID.
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val accPart = treeDocId.substringBefore(';', missingDelimiterValue = "")
                if (accPart.startsWith("acc=")) listOf(accPart) else emptyList()
            } catch (e: Exception) {
                Log.w(logTag, "driveSearch: could not derive root ID from treeUri: $e")
                emptyList()
            }
        }
        if (rootIds.isEmpty()) {
            Log.w(logTag, "driveSearch: no root IDs for authority=$authority name=$name")
            return null
        }

        for (rootId in rootIds) {
            try {
                val searchUri = DocumentsContract.buildSearchDocumentsUri(authority, rootId, name)
                context.contentResolver.query(
                    searchUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0) ?: return@use
                        val displayName = cursor.getString(1) ?: return@use
                        if (displayName == name) {
                            Log.d(logTag, "driveSearch hit '$name' rootId=$rootId docId=$docId")
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            return DocumentFile.fromSingleUri(context, docUri)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(logTag, "driveSearch failed rootId=$rootId name=$name: $e")
            }
        }
        return null
    }

    fun resolveDocument(treeUri: Uri, relativePath: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (relativePath.isBlank()) {
            return root
        }
        var current: DocumentFile? = root
        relativePath.replace('\\', '/').split('/')
            .filter { it.isNotBlank() }
            .forEach { segment ->
                current = current?.let { findChildDocumentByName(treeUri, it, segment) }
            }
        return current
    }

    fun resolveIndexedDocument(treeUri: Uri, index: LibraryIndex, indexedPath: String): DocumentFile? {
        val relativePath = relativePathFromIndex(index.libraryRoot, indexedPath) ?: return null
        val storedBasePath = preferences.getString(KEY_INDEX_BASE_PATH, null)
        val indexBasePath = storedBasePath
            ?.takeUnless { it == ROOT_INDEX_BASE_PATH }
            ?.trim('/')
            .orEmpty()
        val candidates = linkedSetOf<String>()
        candidates += relativePath

        val strippedFirstSegment = relativePath.substringAfter('/', missingDelimiterValue = "")
        if (strippedFirstSegment.isNotBlank()) {
            candidates += strippedFirstSegment
        }

        if (indexBasePath.isNotBlank()) {
            candidates += "$indexBasePath/$relativePath"
            if (strippedFirstSegment.isNotBlank()) {
                candidates += "$indexBasePath/$strippedFirstSegment"
            }
        }

        return candidates.firstNotNullOfOrNull { candidate -> resolveDocument(treeUri, candidate) }
    }

    fun resolveBookCoverDocument(treeUri: Uri, index: LibraryIndex, book: BookEntry): DocumentFile? {
        coverDocCache[book.bookId]?.let { return it }

        loadPersistedDoc(treeUri, "cover:${book.bookId}")?.let { doc ->
            coverDocCache[book.bookId] = doc
            return doc
        }

        val coverPath = book.coverLocalPath
        if (coverPath != null) {
            resolveIndexedDocument(treeUri, index, coverPath)?.let { doc ->
                coverDocCache[book.bookId] = doc
                persistDocId("cover:${book.bookId}", doc)
                return doc
            }
        }

        val bookDirectory = resolveBookDirectory(treeUri, index, book) ?: return null
        val preferredExtension = coverPath
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            .orEmpty()

        // Try exact filename match first (handles spaces in cover names)
        if (coverPath != null) {
            val fileName = coverPath.substringAfterLast('/')
            bookDirectory.listFiles().firstOrNull { child ->
                !child.isDirectory && child.name.orEmpty().equals(fileName, ignoreCase = true)
            }?.let { doc ->
                coverDocCache[book.bookId] = doc
                persistDocId("cover:${book.bookId}", doc)
                return doc
            }
        }

        return bookDirectory.listFiles().firstOrNull { child ->
            !child.isDirectory && when {
                preferredExtension.isNotBlank() -> child.name.orEmpty().lowercase().endsWith(".$preferredExtension")
                else -> child.name.orEmpty().lowercase().endsWith(".jpg") ||
                    child.name.orEmpty().lowercase().endsWith(".jpeg") ||
                    child.name.orEmpty().lowercase().endsWith(".png") ||
                    child.name.orEmpty().lowercase().endsWith(".webp")
            }
        }?.also { doc ->
            coverDocCache[book.bookId] = doc
            persistDocId("cover:${book.bookId}", doc)
        }
    }

    /**
     * Resolves a cover image URI for [book], with a three-level fallback:
     * 1. Local cached file in offlineRoot (fast, no network)
     * 2. SAF DocumentFile URI (works for books in Drive's first listing page)
     * 3. Drive REST API download (works for all books, stores result in cache)
     */
    suspend fun resolveCoverUri(book: BookEntry): Uri? = withContext(Dispatchers.IO) {
        // 1. Already cached locally?
        val cached = cachedCoverFile(book)
        if (cached != null) return@withContext android.net.Uri.fromFile(cached)

        // 2. Resolve via SAF.
        val treeUri = loadSavedTreeUri()
        val index = loadCachedIndex()
        if (treeUri != null && index != null) {
            resolveBookCoverDocument(treeUri, index, book)?.uri?.let { return@withContext it }
        }

        // 3. Download from Drive REST API using the cover file ID stored in the index.
        val driveRef = book.driveFiles["cover"] ?: return@withContext null
        val token = fetchDriveToken() ?: return@withContext null
        val ext = driveRef.name.substringAfterLast('.', missingDelimiterValue = "jpg").lowercase()
        val coverFile = File(File(offlineRoot, book.bookId), "cover.$ext")
        coverFile.parentFile?.mkdirs()
        return@withContext if (downloadDriveFile(driveRef.id, token, coverFile)) {
            android.net.Uri.fromFile(coverFile)
        } else {
            coverFile.delete()
            null
        }
    }

    fun resolveBookFormatDocument(treeUri: Uri, index: LibraryIndex, book: BookEntry, format: String): DocumentFile? {
        val cacheKey = "${book.bookId}/$format"
        formatDocCache[cacheKey]?.let { return it }

        loadPersistedDoc(treeUri, "fmt:$cacheKey")?.let { doc ->
            formatDocCache[cacheKey] = doc
            return doc
        }

        val sourcePath = book.formatFiles[format]

        // Try exact path first
        if (sourcePath != null) {
            resolveIndexedDocument(treeUri, index, sourcePath)?.let { doc ->
                formatDocCache[cacheKey] = doc
                persistDocId("fmt:$cacheKey", doc)
                return doc
            }
        }

        // Fallback: navigate to the book directory and match by file name or extension
        val bookDirectory = resolveBookDirectory(treeUri, index, book) ?: run {
            Log.w(logTag, "resolveFormat: bookDirectory null for book=${book.bookId} format=$format")
            return null
        }

        // First try matching exact file name from the path (handles spaces in filenames)
        if (sourcePath != null) {
            val fileName = sourcePath.substringAfterLast('/')
            bookDirectory.listFiles().firstOrNull { child ->
                !child.isDirectory && child.name.orEmpty().equals(fileName, ignoreCase = true)
            }?.let { doc ->
                formatDocCache[cacheKey] = doc
                persistDocId("fmt:$cacheKey", doc)
                return doc
            }
        }

        // Last resort: match by extension only
        return bookDirectory.listFiles().firstOrNull { child ->
            !child.isDirectory && child.name.orEmpty().lowercase().endsWith(".${format.lowercase()}")
        }?.also { doc ->
            formatDocCache[cacheKey] = doc
            persistDocId("fmt:$cacheKey", doc)
        }
    }

    private fun resolveBookDirectory(treeUri: Uri, index: LibraryIndex, book: BookEntry): DocumentFile? {
        bookDirDocCache[book.bookId]?.let { return it }

        // Try persisted SAF doc ID (survives Drive SAF 50-item pagination limit)
        loadPersistedDoc(treeUri, "dir:${book.bookId}")?.let { doc ->
            bookDirDocCache[book.bookId] = doc
            return doc
        }

        val candidateDirectories = linkedSetOf<String>()
        book.coverLocalPath
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { candidateDirectories += it }
        book.formatFiles.values.firstOrNull()
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { candidateDirectories += it }
        if (book.relativeDir.isNotBlank()) {
            candidateDirectories += "books/${book.relativeDir.trim('/')}"
            candidateDirectories += book.relativeDir.trim('/')
        }

        return candidateDirectories.firstNotNullOfOrNull { candidate ->
            resolveIndexedDocument(treeUri, index, candidate)
        }?.also { dir ->
            bookDirDocCache[book.bookId] = dir
            persistDocId("dir:${book.bookId}", dir)
        }
    }

    suspend fun logResolutionSnapshot(treeUri: Uri, index: LibraryIndex) = withContext(Dispatchers.IO) {
        val storedBasePath = preferences.getString(KEY_INDEX_BASE_PATH, null)
        val root = DocumentFile.fromTreeUri(context, treeUri)
        val rootChildren = root?.listFiles()?.take(12)?.joinToString { child ->
            val type = if (child.isDirectory) "dir" else "file"
            "$type:${child.name}"
        }.orEmpty()
        val booksDir = resolveDocument(treeUri, "books")
        val firstBook = index.books.firstOrNull()
        val formatPath = firstBook?.formatFiles?.entries?.firstOrNull()?.value
        val coverPath = firstBook?.coverLocalPath
        val resolvedFormat = firstBook?.let { book ->
            book.formatFiles.keys.firstOrNull()?.let { resolveBookFormatDocument(treeUri, index, book, it) }
        }
        val resolvedCover = firstBook?.let { book -> resolveBookCoverDocument(treeUri, index, book) }
        val bookDir = firstBook?.let { resolveBookDirectory(treeUri, index, it) }

        Log.d(logTag, "snapshot treeUri=$treeUri basePath=$storedBasePath rootChildren=[$rootChildren]")
        Log.d(logTag, "snapshot booksDir=${booksDir?.name} firstBook=${firstBook?.title} relDir=${firstBook?.relativeDir}")
        Log.d(logTag, "snapshot formatPath=$formatPath resolvedFormat=${resolvedFormat?.name} formatUri=${resolvedFormat?.uri}")
        Log.d(logTag, "snapshot coverPath=$coverPath resolvedCover=${resolvedCover?.name} coverUri=${resolvedCover?.uri}")
        Log.d(logTag, "snapshot bookDir=${bookDir?.name} bookDirUri=${bookDir?.uri}")

        // Single-pass warm: caches the ~50 book dirs Drive returns in the first page.
        // Books beyond page 1 are resolved on-demand via driveSearchFallback in findChildDocumentByName.
        val booksFolder = resolveDocument(treeUri, "books")
        if (booksFolder != null) {
            warmBookDirCache(treeUri, booksFolder, index)
        } else {
            Log.w(logTag, "snapshot warm: could not resolve books/ folder")
        }
    }

    /**
     * Single-pass warmup: caches the ~50 book directories that Drive returns on the first
     * listing page. Books beyond the first page are discovered lazily via the Drive search
     * fallback in [findChildDocumentByName] when a user first accesses them, and then
     * persisted permanently to [docIdPrefs].
     */
    private fun warmBookDirCache(treeUri: Uri, booksFolder: DocumentFile, index: LibraryIndex) {
        val parentId = try {
            DocumentsContract.getDocumentId(booksFolder.uri)
        } catch (e: Exception) {
            Log.w(logTag, "warmBookDirCache: getDocumentId failed: $e")
            return
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val relDirToBookId = index.books.associate { it.relativeDir.trim('/') to it.bookId }
        val totalBooks = index.books.size
        val editor = docIdPrefs.edit()

        // Attempt paginated listing via Bundle QUERY_ARG_OFFSET (API 26+).
        // Drive may ignore these args and return the same 50 items every time.
        // We detect that case by tracking seen document IDs and stopping when
        // a batch contains zero new (unseen) documents.
        val seenDocIds = mutableSetOf<String>()
        var offset = 0
        val pageSize = 500   // request large batches; Drive will cap at its own limit
        var totalFound = 0
        var pass = 0

        while (true) {
            pass++
            var batchTotal = 0
            var batchNew = 0

            val queryArgs = android.os.Bundle().apply {
                putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, pageSize)
            }

            val cursor = try {
                context.contentResolver.query(childrenUri, projection, queryArgs, null)
            } catch (e: Exception) {
                Log.w(logTag, "warmBooks pass=$pass query failed: $e")
                break
            } ?: break

            cursor.use { c ->
                batchTotal = c.count
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (!seenDocIds.add(docId)) continue   // duplicate → Drive ignored offset
                    batchNew++
                    val bookId = relDirToBookId[name] ?: continue
                    if (!bookDirDocCache.containsKey(bookId)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val doc = DocumentFile.fromSingleUri(context, docUri) ?: continue
                        bookDirDocCache[bookId] = doc
                        editor.putString("dir:$bookId", docId)
                        totalFound++
                    }
                }
            }

            Log.d(logTag, "warmBooks pass=$pass offset=$offset batchTotal=$batchTotal batchNew=$batchNew totalCached=$totalFound/$totalBooks")

            if (batchNew == 0 || batchTotal == 0) break   // no new items: pagination exhausted or not supported
            offset += batchTotal
        }

        editor.apply()
        Log.d(logTag, "warmBooks: done after $pass pass(es). Cached $totalFound new dirs (${bookDirDocCache.size}/$totalBooks total).")
    }

    private fun findIndexDocument(treeUri: Uri): IndexedDocument? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        resolveDocument(treeUri, "index.json")?.let { return IndexedDocument(it, "") }

        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty()) {
            val (current, currentPath) = queue.removeFirst()
            current.listFiles().forEach { child ->
                val childPath = listOf(currentPath, child.name.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString("/")
                if (child.isFile && child.name == "index.json") {
                    return IndexedDocument(child, currentPath)
                }
                if (child.isDirectory) {
                    queue.add(child to childPath)
                }
            }
        }
        return null
    }

    fun relativePathFromIndex(indexRoot: String, indexedPath: String): String? {
        val normalizedPath = indexedPath.replace('\\', '/')
        val looksAbsolute = Regex("^[A-Za-z]:/").containsMatchIn(normalizedPath) || normalizedPath.startsWith("/")

        if (indexRoot.isNotBlank()) {
            val normalizedRoot = indexRoot.replace('\\', '/').trimEnd('/')
            if (normalizedPath.startsWith(normalizedRoot)) {
                return normalizedPath.removePrefix(normalizedRoot).trimStart('/')
            }
            return if (looksAbsolute) null else normalizedPath.trimStart('/')
        }

        return if (looksAbsolute) null else normalizedPath.trimStart('/')
    }

    private fun offlineFile(bookId: String, format: String, sourcePath: String): File {
        val extension = sourcePath.substringAfterLast('.', format)
        val directory = File(offlineRoot, bookId)
        return File(directory, "$format.$extension")
    }

    // --------------- Drive REST API helpers (fallback when SAF listing is limited) ---------------

    /**
     * Fetches a Google Drive OAuth access token using the Google account the user previously
     * linked via the account picker. On Android 8+, [AccountManager.getAccountsByType] returns
     * nothing for third-party apps until the user explicitly picks an account via
     * [AccountManager.newChooseAccountIntent]; this method uses the stored name instead.
     *
     * Returns null if no account has been linked yet (caller should trigger account picker),
     * or if the token cannot be obtained.
     */
    suspend fun fetchDriveToken(): String? = withContext(Dispatchers.IO) {
        val accountName = preferences.getString(KEY_GOOGLE_ACCOUNT_NAME, null) ?: run {
            Log.w(logTag, "fetchDriveToken: no saved Google account — account picker needed")
            return@withContext null
        }
        val account = android.accounts.Account(accountName, "com.google")
        try {
            val future = AccountManager.get(context).getAuthToken(
                account,
                "oauth2:https://www.googleapis.com/auth/drive.readonly",
                null,
                false,
                null,
                null,
            )
            val bundle = future.result ?: return@withContext null
            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
            if (token != null) {
                Log.d(logTag, "fetchDriveToken: got token (len=${token.length})")
                return@withContext token
            }
            // Drive consent not yet granted — launch the system OAuth dialog.
            @Suppress("DEPRECATION")
            val authIntent = bundle.getParcelable<Intent>(AccountManager.KEY_INTENT)
            if (authIntent != null) {
                Log.d(logTag, "fetchDriveToken: launching Drive consent dialog")
                context.startActivity(authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                Log.w(logTag, "fetchDriveToken: no token and no KEY_INTENT in bundle")
            }
            null
        } catch (e: Exception) {
            Log.w(logTag, "fetchDriveToken failed: $e")
            null
        }
    }

    /**
     * Downloads a Drive file by its file ID directly via the Drive REST API v3.
     * Requires a valid [token] from [fetchDriveToken]. The file is streamed directly to
     * [outputFile]; on failure the partial file is not deleted (caller is responsible).
     * Returns true on success.
     */
    private fun downloadDriveFile(fileId: String, token: String, outputFile: File): Boolean {
        val url = URL("https://www.googleapis.com/drive/v3/files/${fileId}?alt=media")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(logTag, "downloadDriveFile: HTTP $code for fileId=$fileId")
                return false
            }
            conn.inputStream.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.w(logTag, "downloadDriveFile failed fileId=$fileId: $e")
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Searches the authenticated user's entire Drive for a file with an exact [fileName].
     * Used when the index.json does not contain a Drive file ID for the book format
     * (i.e. the index was generated without --upload-files).
     * Returns the Drive API file ID on success, or null if not found / on error.
     * Results are cached in [driveApiIdPrefs] by the caller to avoid repeat searches.
     */
    private suspend fun searchDriveFileByName(fileName: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            val escaped = fileName.replace("'", "\\'")
            val query = URLEncoder.encode("name='$escaped' and trashed=false", "UTF-8")
            val url = URL("https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id)&spaces=drive&pageSize=1")
            val conn = url.openConnection() as HttpURLConnection
            return@withContext try {
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(logTag, "searchDriveFileByName: HTTP $code for name='$fileName'")
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().readText()
                val filesArr = org.json.JSONObject(body).optJSONArray("files")
                if (filesArr != null && filesArr.length() > 0) {
                    filesArr.getJSONObject(0).optString("id").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(logTag, "searchDriveFileByName failed name='$fileName': $e")
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "calibre_drive_reader"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_INDEX_BASE_PATH = "index_base_path"
        private const val ROOT_INDEX_BASE_PATH = "."
        private const val KEY_GOOGLE_ACCOUNT_NAME = "google_account_name"
    }
}
