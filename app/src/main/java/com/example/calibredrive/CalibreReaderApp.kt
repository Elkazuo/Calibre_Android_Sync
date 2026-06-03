package com.example.calibredrive

import android.accounts.AccountManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.calibredrive.ui.LibraryScreen
import com.example.calibredrive.ui.BookDetailsDialog

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CalibreReaderApp(viewModel: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedBook by remember { mutableStateOf<BookEntry?>(null) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.onTreePicked(uri)
        }
    }

    // Account picker: required on Android 8+ so the app gains visibility to the Google account.
    val accountPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!name.isNullOrEmpty()) {
            viewModel.onGoogleAccountPicked(name)
        } else {
            viewModel.consumeAccountPicker()
        }
    }

    LaunchedEffect(uiState.pendingAccountPicker) {
        if (uiState.pendingAccountPicker) {
            accountPicker.launch(viewModel.buildAccountPickerIntent())
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeStatusMessage()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeErrorMessage()
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text("Calibre Drive Reader") },
                actions = {
                    androidx.compose.material3.TextButton(onClick = { treePicker.launch(null) }) {
                        androidx.compose.material3.Text("Elegir carpeta")
                    }
                    androidx.compose.material3.TextButton(onClick = { viewModel.refresh() }) {
                        androidx.compose.material3.Text("Actualizar")
                    }
                },
            )
        },
    ) { paddingValues ->
        LibraryScreen(
            modifier = Modifier.padding(paddingValues),
            state = uiState,
            coverUriForBook = { book -> viewModel.resolveCoverUri(book) },
            cachedFileForBook = { book, format -> viewModel.cachedBookFile(book.bookId, format) },
            onSearchQueryChanged = viewModel::setSearchQuery,
            onCacheFormat = viewModel::cacheBookFormat,
            onOpenCachedBook = { file -> openCachedBook(context, file) },
            onBookSelected = { selectedBook = it },
            onSortModeChanged = viewModel::setSortMode,
            onTagToggled = viewModel::toggleTag,
            onEstadoToggled = viewModel::toggleEstado,
            onClearFilters = viewModel::clearFilters,
        )
    }

    selectedBook?.let { book ->
        BookDetailsDialog(
            book = book,
            coverUriForBook = { selected -> viewModel.resolveCoverUri(selected) },
            cachedFileForBook = { selected, format -> viewModel.cachedBookFile(selected.bookId, format) },
            onCacheFormat = viewModel::cacheBookFormat,
            onOpenCachedBook = { file -> openCachedBook(context, file) },
            onOpenExternalLink = { url -> openExternalLink(context, url) },
            onDismiss = { selectedBook = null },
        )
    }
}
