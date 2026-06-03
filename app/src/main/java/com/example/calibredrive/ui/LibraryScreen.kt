package com.example.calibredrive.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.calibredrive.BookEntry
import com.example.calibredrive.BookSortMode
import com.example.calibredrive.LibraryUiState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    state: LibraryUiState,
    coverUriForBook: suspend (BookEntry) -> Uri?,
    cachedFileForBook: (BookEntry, String) -> File?,
    onSearchQueryChanged: (String) -> Unit,
    onCacheFormat: (BookEntry, String) -> Unit,
    onOpenCachedBook: (File) -> Unit,
    onBookSelected: (BookEntry) -> Unit,
    onSortModeChanged: (BookSortMode) -> Unit,
    onTagToggled: (String) -> Unit = {},
    onEstadoToggled: (String) -> Unit = {},
    onClearFilters: () -> Unit = {},
) {
    val tagCounts = remember(state.index) {
        state.index?.books.orEmpty()
            .flatMap { it.tags }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
    }
    val allTags = remember(state.index) {
        tagCounts.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }
    val allEstados = remember(state.index) {
        state.index?.books.orEmpty()
            .mapNotNull { (it.customColumns["#estados"] as? JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() }
            .toSortedSet()
            .toList()
    }

    val books = remember(state.index, state.searchQuery, state.sortMode, state.selectedTags, state.selectedEstados) {
        val source = state.index?.books.orEmpty()
        val filter = parseQuery(state.searchQuery)
        var filtered = if (filter.plainText.isBlank()) source
            else source.filter { searchableText(it).contains(filter.plainText) }
        filter.tagFilter?.let { q ->
            filtered = filtered.filter { book -> book.tags.any { it.contains(q, ignoreCase = true) } }
        }
        filter.authorFilter?.let { q ->
            filtered = filtered.filter { book -> book.authors.any { it.contains(q, ignoreCase = true) } }
        }
        filter.seriesFilter?.let { q ->
            filtered = filtered.filter { book -> book.series?.contains(q, ignoreCase = true) == true }
        }
        filter.publisherFilter?.let { q ->
            filtered = filtered.filter { book -> book.publisher?.contains(q, ignoreCase = true) == true }
        }
        filter.languageFilter?.let { q ->
            filtered = filtered.filter { book -> book.languages.any { it.contains(q, ignoreCase = true) } }
        }
        if (filter.ratingOp != null && filter.ratingVal != null) {
            filtered = filtered.filter { book ->
                val r = book.rating ?: return@filter false
                when (filter.ratingOp) {
                    ">=" -> r >= filter.ratingVal
                    "<=" -> r <= filter.ratingVal
                    "!=" -> r != filter.ratingVal
                    ">" -> r > filter.ratingVal
                    "<" -> r < filter.ratingVal
                    else -> r == filter.ratingVal
                }
            }
        }
        if (state.selectedTags.isNotEmpty()) {
            filtered = filtered.filter { book -> state.selectedTags.all { tag -> tag in book.tags } }
        }
        if (state.selectedEstados.isNotEmpty()) {
            filtered = filtered.filter { book ->
                val estado = (book.customColumns["#estados"] as? JsonPrimitive)?.contentOrNull
                estado in state.selectedEstados
            }
        }

        when (state.sortMode) {
            BookSortMode.Title -> filtered.sortedBy { it.title.lowercase() }
            BookSortMode.TitleSort -> filtered.sortedBy { it.titleSort.lowercase() }
            BookSortMode.Author -> filtered.sortedWith(compareBy<BookEntry> { it.authors.firstOrNull().orEmpty().lowercase() }.thenBy { it.title.lowercase() })
            BookSortMode.Series -> filtered.sortedWith(compareBy<BookEntry> { it.series.orEmpty().lowercase() }.thenBy { it.seriesIndex ?: Double.MAX_VALUE }.thenBy { it.title.lowercase() })
            BookSortMode.RatingDesc -> filtered.sortedWith(compareByDescending<BookEntry> { it.rating ?: Double.MIN_VALUE }.thenBy { it.title.lowercase() })
            BookSortMode.PubDateDesc -> filtered.sortedWith(compareByDescending<BookEntry> { it.pubdate.orEmpty() }.thenBy { it.title.lowercase() })
            BookSortMode.TimestampDesc -> filtered.sortedWith(compareByDescending<BookEntry> { it.timestamp.orEmpty() }.thenBy { it.title.lowercase() })
        }
    }

    var showTagSheet by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Buscar en biblioteca") },
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(sortModes) { sortMode ->
                FilterChip(
                    selected = state.sortMode == sortMode.value,
                    onClick = { onSortModeChanged(sortMode.value) },
                    label = { Text(sortMode.label) },
                )
            }
        }

        if (allEstados.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Estado", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(allEstados) { estado ->
                    FilterChip(
                        selected = estado in state.selectedEstados,
                        onClick = { onEstadoToggled(estado) },
                        label = { Text(estado) },
                    )
                }
            }
        }

        if (allTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.selectedTags.isEmpty()) "Etiquetas"
                           else "Etiquetas (${state.selectedTags.size})",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showTagSheet = true }) { Text("Elegir…") }
            }
            if (state.selectedTags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.selectedTags.toList().sorted()) { tag ->
                        FilterChip(
                            selected = true,
                            onClick = { onTagToggled(tag) },
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }

        val hasActiveFilters = state.selectedTags.isNotEmpty() || state.selectedEstados.isNotEmpty()
        if (hasActiveFilters) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onClearFilters) { Text("Limpiar filtros") }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (state.index == null) {
            EmptyLibraryState()
        } else {
            Text(
                text = "${state.index.bookCount} libros indexados",
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(books, key = { it.bookId }) { book ->
                    BookCard(
                        book = book,
                        coverUriForBook = coverUriForBook,
                        cachedFileForBook = cachedFileForBook,
                        onCacheFormat = onCacheFormat,
                        onOpenCachedBook = onOpenCachedBook,
                        onBookSelected = onBookSelected,
                    )
                }
            }
        }
    }

    if (showTagSheet) {
        ModalBottomSheet(onDismissRequest = { showTagSheet = false }) {
            TagPickerSheet(
                allTags = allTags,
                tagCounts = tagCounts,
                selectedTags = state.selectedTags,
                onTagToggled = onTagToggled,
            )
        }
    }
}

@Composable
private fun EmptyLibraryState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "No hay biblioteca cargada")
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Usa 'Elegir carpeta' y selecciona la carpeta raíz sincronizada en Drive.")
        }
    }
}

private data class SortMode(val label: String, val value: BookSortMode)

private val sortModes = listOf(
    SortMode("Título", BookSortMode.Title),
    SortMode("Orden título", BookSortMode.TitleSort),
    SortMode("Autor", BookSortMode.Author),
    SortMode("Serie", BookSortMode.Series),
    SortMode("Valoración", BookSortMode.RatingDesc),
    SortMode("Fecha pub.", BookSortMode.PubDateDesc),
    SortMode("Actualizado", BookSortMode.TimestampDesc),
)

@Composable
private fun BookCard(
    book: BookEntry,
    coverUriForBook: suspend (BookEntry) -> Uri?,
    cachedFileForBook: (BookEntry, String) -> File?,
    onCacheFormat: (BookEntry, String) -> Unit,
    onOpenCachedBook: (File) -> Unit,
    onBookSelected: (BookEntry) -> Unit,
) {
    val coverUri by produceState<Uri?>(initialValue = null, key1 = book.bookId) {
        value = withContext(Dispatchers.IO) { coverUriForBook(book) }
    }

    ElevatedCard(onClick = { onBookSelected(book) }) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .height(132.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFD7E8F5)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = book.title.take(2).uppercase())
                if (coverUri != null) {
                    AsyncImage(
                        model = coverUri,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = book.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = book.authors.joinToString(", ").ifBlank { "Sin autores" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                book.series?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Serie: $it")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = metadataLine(book), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleFormats(book).take(3).forEach { format ->
                        AssistChip(onClick = {}, label = { Text(format.uppercase()) })
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val preferredFormat = preferredFormat(book)
                    if (preferredFormat != null) {
                        val cachedFile = cachedFileForBook(book, preferredFormat)
                        if (cachedFile == null) {
                            Button(onClick = { onCacheFormat(book, preferredFormat) }) {
                                Text(text = "Guardar ${preferredFormat.uppercase()}")
                            }
                        } else {
                            Button(onClick = { onOpenCachedBook(cachedFile) }) {
                                Text(text = "Abrir ${preferredFormat.uppercase()}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun searchableText(book: BookEntry): String {
    return buildString {
        append(book.title)
        append(' ')
        append(book.titleSort)
        append(' ')
        append(book.authors.joinToString(" "))
        append(' ')
        append(book.authorSort.joinToString(" "))
        append(' ')
        append(book.series.orEmpty())
        append(' ')
        append(book.tags.joinToString(" "))
        append(' ')
        append(book.languages.joinToString(" "))
        append(' ')
        append(book.publisher.orEmpty())
        append(' ')
        append(book.identifiers.entries.joinToString(" ") { "${it.key} ${it.value}" })
        append(' ')
        append(book.customColumns.entries.joinToString(" ") { "${it.key} ${it.value}" })
    }.lowercase()
}

private fun metadataLine(book: BookEntry): String {
    val parts = buildList {
        book.rating?.let { add("★ ${it.toString().trimEnd('0').trimEnd('.')}") }
        book.languages.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
        book.pubdate?.let { add("Pub: $it") }
        book.timestamp?.let { add("Actualizado: $it") }
        book.tags.takeIf { it.isNotEmpty() }?.let { add(it.take(3).joinToString(", ")) }
    }
    return parts.joinToString(" • ").ifBlank { "Sin metadatos destacados" }
}

@Composable
fun BookDetailsDialog(
    book: BookEntry,
    coverUriForBook: suspend (BookEntry) -> Uri?,
    cachedFileForBook: (BookEntry, String) -> File?,
    onCacheFormat: (BookEntry, String) -> Unit,
    onOpenCachedBook: (File) -> Unit,
    onOpenExternalLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val coverUri by produceState<Uri?>(initialValue = null, key1 = book.bookId) {
        value = withContext(Dispatchers.IO) { coverUriForBook(book) }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(104.dp)
                            .height(156.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFD7E8F5)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = book.title.take(2).uppercase())
                        if (coverUri != null) {
                            AsyncImage(
                                model = coverUri,
                                contentDescription = book.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        DetailLine("Autores", book.authors.joinToString(", ").ifBlank { "Sin autores" })
                        DetailLine("Serie", seriesLabel(book))
                        DetailLine("Valoración", book.rating?.toString() ?: "No disponible")
                        DetailLine("Idiomas", book.languages.joinToString(", ").ifBlank { "No disponible" })
                        DetailLine("Editorial", book.publisher ?: "No disponible")
                    }
                }

                DetailLine("Título ordenado", book.titleSort)
                DetailLine("Orden autores", book.authorSort.joinToString(", ").ifBlank { "No disponible" })
                DetailLine("Fecha publicación", book.pubdate ?: "No disponible")
                DetailLine("Fecha actualización", book.timestamp ?: "No disponible")
                DetailLine("Etiquetas", book.tags.joinToString(", ").ifBlank { "Sin etiquetas" })
                DetailLine("Identificadores", book.identifiers.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "No disponible" })
                DetailLine("Ruta relativa", book.relativeDir)

                Text(text = "Formatos", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleFormats(book).forEach { format ->
                        val cachedFile = cachedFileForBook(book, format)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = format.uppercase(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val driveLink = book.driveFiles[format]?.webViewLink
                            if (cachedFile == null) {
                                Button(onClick = { onCacheFormat(book, format) }) {
                                    Text("Guardar")
                                }
                            } else {
                                Button(onClick = { onOpenCachedBook(cachedFile) }) {
                                    Text("Abrir")
                                }
                            }
                            if (driveLink != null) {
                                Button(onClick = { onOpenExternalLink(driveLink) }) {
                                    Text("Drive")
                                }
                            }
                        }
                    }
                }

                DetailLine("Campos personalizados", book.customColumns.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "Sin campos" })
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, fontWeight = FontWeight.SemiBold)
        Text(text = value)
    }
}

private fun visibleFormats(book: BookEntry): List<String> {
    return (book.formatFiles.keys + book.driveFiles.keys)
        .filterNot { it == "cover" }
        .distinct()
        .sorted()
}

private fun seriesLabel(book: BookEntry): String {
    val series = book.series ?: return "Sin serie"
    val index = book.seriesIndex?.let { " #${it.toString().trimEnd('0').trimEnd('.') }" }.orEmpty()
    return "$series$index"
}

private fun preferredFormat(book: BookEntry): String? {
    val priorities = listOf("epub", "pdf", "mobi", "azw3", "docx")
    return priorities.firstOrNull { book.formatFiles.containsKey(it) }
        ?: book.formatFiles.keys.sorted().firstOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagPickerSheet(
    allTags: List<String>,
    tagCounts: Map<String, Int>,
    selectedTags: Set<String>,
    onTagToggled: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, allTags) {
        if (search.isBlank()) allTags
        else allTags.filter { it.contains(search.trim(), ignoreCase = true) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Etiquetas",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Buscar etiqueta") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(filtered) { tag ->
                FilterChip(
                    selected = tag in selectedTags,
                    onClick = { onTagToggled(tag) },
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(tag, modifier = Modifier.weight(1f))
                            tagCounts[tag]?.let {
                                Text(
                                    text = "$it",
                                    color = Color.Gray,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private data class SearchFilter(
    val plainText: String,
    val tagFilter: String?,
    val authorFilter: String?,
    val seriesFilter: String?,
    val publisherFilter: String?,
    val languageFilter: String?,
    val ratingOp: String?,
    val ratingVal: Double?,
)

private fun parseQuery(raw: String): SearchFilter {
    if (raw.isBlank()) return SearchFilter("", null, null, null, null, null, null, null)
    val tokens = raw.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
    val plain = mutableListOf<String>()
    var tagFilter: String? = null
    var authorFilter: String? = null
    var seriesFilter: String? = null
    var publisherFilter: String? = null
    var languageFilter: String? = null
    var ratingOp: String? = null
    var ratingVal: Double? = null
    val ratingPattern = Regex("^(>=|<=|!=|>|<|=)?(.+)$")

    for (token in tokens) {
        val colonIdx = token.indexOf(':')
        if (colonIdx > 0) {
            val field = token.substring(0, colonIdx).lowercase()
            val value = token.substring(colonIdx + 1).lowercase()
            when (field) {
                "tag", "tags", "etiqueta", "etiquetas" -> tagFilter = value
                "author", "authors", "autor", "autores" -> authorFilter = value
                "series", "serie" -> seriesFilter = value
                "publisher", "editorial" -> publisherFilter = value
                "language", "lang", "idioma", "idiomas", "languages" -> languageFilter = value
                "rating", "valoracion", "valoración" -> {
                    val m = ratingPattern.find(value)
                    if (m != null) {
                        val op = m.groupValues[1].ifBlank { "=" }
                        val num = m.groupValues[2].toDoubleOrNull()
                        if (num != null) { ratingOp = op; ratingVal = num }
                    }
                }
                else -> plain.add(token)
            }
        } else {
            plain.add(token)
        }
    }
    return SearchFilter(
        plainText = plain.joinToString(" ").lowercase(),
        tagFilter = tagFilter,
        authorFilter = authorFilter,
        seriesFilter = seriesFilter,
        publisherFilter = publisherFilter,
        languageFilter = languageFilter,
        ratingOp = ratingOp,
        ratingVal = ratingVal,
    )
}
