package com.example.calibredrive

import android.accounts.AccountManager
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application.applicationContext)
    private val _uiState = MutableStateFlow(
        LibraryUiState(
            treeUri = store.loadSavedTreeUri()?.toString(),
            index = store.loadCachedIndex(),
        ),
    )
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        if (_uiState.value.treeUri != null && (_uiState.value.index == null || !store.hasResolvedIndexBasePath())) {
            refreshFromSavedTree()
        } else {
            val treeUri = _uiState.value.treeUri?.let(Uri::parse)
            val index = _uiState.value.index
            if (treeUri != null && index != null) {
                viewModelScope.launch {
                    store.logResolutionSnapshot(treeUri, index)
                }
            }
        }
    }

    fun onTreePicked(uri: Uri) {
        store.saveTreeUri(uri)
        _uiState.update { it.copy(loading = true, treeUri = uri.toString(), errorMessage = null) }
        viewModelScope.launch {
            runCatching { store.importIndex(uri) }
                .onSuccess { index ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            index = index,
                            statusMessage = "Índice importado y cacheado",
                            errorMessage = null,
                        )
                    }
                    viewModelScope.launch {
                        store.logResolutionSnapshot(uri, index)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            errorMessage = error.message ?: "No se pudo leer index.json",
                        )
                    }
                }
        }
    }

    fun refresh() {
        val treeUri = _uiState.value.treeUri?.let(Uri::parse) ?: return
        refreshFromUri(treeUri)
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSortMode(sortMode: BookSortMode) {
        _uiState.update { it.copy(sortMode = sortMode) }
    }

    fun toggleTag(tag: String) {
        _uiState.update { state ->
            val current = state.selectedTags
            state.copy(selectedTags = if (tag in current) current - tag else current + tag)
        }
    }

    fun toggleEstado(estado: String) {
        _uiState.update { state ->
            val current = state.selectedEstados
            state.copy(selectedEstados = if (estado in current) current - estado else current + estado)
        }
    }

    fun clearFilters() {
        _uiState.update { it.copy(selectedTags = emptySet(), selectedEstados = emptySet()) }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun consumeErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Returns the Intent to show the system account picker (Android 8+ account visibility). */
    fun buildAccountPickerIntent(): android.content.Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)

    /** Called with the account name the user picked. Stores it so Drive API calls can proceed. */
    fun onGoogleAccountPicked(accountName: String) {
        store.saveGoogleAccountName(accountName)
        _uiState.update {
            it.copy(
                pendingAccountPicker = false,
                statusMessage = "Cuenta de Google vinculada. Reintenta la descarga.",
            )
        }
    }

    fun consumeAccountPicker() {
        _uiState.update { it.copy(pendingAccountPicker = false) }
    }


    fun cacheBookFormat(book: BookEntry, format: String) {
        val treeUri = _uiState.value.treeUri?.let(Uri::parse) ?: return
        val index = _uiState.value.index ?: return
        _uiState.update { it.copy(syncingBookId = book.bookId, errorMessage = null) }
        viewModelScope.launch {
            runCatching { store.cacheBookFormat(treeUri, index, book, format) }
                .onSuccess { cachedFile ->
                    if (cachedFile == null && !store.hasSavedGoogleAccount()) {
                        // No account linked yet — ask the user to pick one first.
                        _uiState.update {
                            it.copy(
                                syncingBookId = null,
                                pendingAccountPicker = true,
                                statusMessage = "Vincula tu cuenta de Google Drive para descargar libros",
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                syncingBookId = null,
                                statusMessage = if (cachedFile != null) {
                                    "${book.title}: $format guardado offline"
                                } else {
                                    "No se encontró el archivo para ${book.title}"
                                },
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            syncingBookId = null,
                            errorMessage = error.message ?: "No se pudo cachear el libro",
                        )
                    }
                }
        }
    }

    fun resolveCoverDocument(book: BookEntry): DocumentFile? {
        val treeUri = _uiState.value.treeUri?.let(Uri::parse) ?: return null
        val index = _uiState.value.index ?: return null
        return runCatching {
            store.resolveBookCoverDocument(treeUri, index, book)
        }.onFailure { e ->
            android.util.Log.e("CalibreDrive", "cover err book=${book.bookId} title=${book.title}: $e")
        }.getOrNull()
    }

    suspend fun resolveCoverUri(book: BookEntry): Uri? {
        return runCatching { store.resolveCoverUri(book) }
            .onFailure { e ->
                android.util.Log.e("CalibreDrive", "resolveCoverUri err book=${book.bookId}: $e")
            }
            .getOrNull()
    }

    fun cachedBookFile(bookId: String, format: String) = store.cachedBookFile(bookId, format)

    private fun refreshFromSavedTree() {
        val treeUri = _uiState.value.treeUri?.let(Uri::parse) ?: return
        refreshFromUri(treeUri)
    }

    private fun refreshFromUri(treeUri: Uri) {
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { store.importIndex(treeUri) }
                .onSuccess { index ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            index = index,
                            statusMessage = "Biblioteca actualizada",
                            errorMessage = null,
                        )
                    }
                    viewModelScope.launch {
                        store.logResolutionSnapshot(treeUri, index)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            errorMessage = error.message ?: "No se pudo actualizar la biblioteca",
                        )
                    }
                }
        }
    }
}
