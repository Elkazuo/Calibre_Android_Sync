package com.example.calibredrive

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "BookOpener"

fun openCachedBook(context: Context, file: File) {
    val mimeType = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        else -> "*/*"
    }
    // On Android 10+ use MediaStore.Downloads so every reader app gets a standard
    // content://media URI — FileProvider URIs are not handled by many e-reader apps
    // (including eBoox) that pre-date the FileProvider model.
    val openUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        copyToMediaStoreDownloads(context, file, mimeType) ?: run {
            Log.e(TAG, "MediaStore copy failed, falling back to FileProvider")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    } else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(openUri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e(TAG, "startActivity failed for uri=$openUri: $e")
    }
}

/**
 * Copies [file] into MediaStore.Downloads and returns its content URI.
 * If a file with the same name and size already exists it is reused, avoiding
 * duplicate copies on every open.
 */
private fun copyToMediaStoreDownloads(context: Context, file: File, mimeType: String): Uri? {
    return try {
        val resolver = context.contentResolver
        val displayName = file.name
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // Reuse an existing entry with matching name and size.
        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val size = cursor.getLong(1)
                if (size == file.length()) {
                    val id = cursor.getLong(0)
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }

        // Insert a new entry.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "MediaStore.insert returned null for $displayName")
            return null
        }
        resolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        Log.d(TAG, "Copied to MediaStore Downloads: $itemUri size=${file.length()}")
        itemUri
    } catch (e: Exception) {
        Log.e(TAG, "copyToMediaStoreDownloads failed: $e")
        null
    }
}

fun openExternalLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Abrir enlace"))
}
