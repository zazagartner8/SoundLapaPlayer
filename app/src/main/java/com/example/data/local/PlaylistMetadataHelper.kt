package com.example.data.local

import androidx.compose.runtime.Immutable

@Immutable
data class ParsedPlaylistDescription(
    val importSource: String?,
    val userDescription: String,
)

/**
 * Separates structured import provenance ("Импорт (...)") from user-authored playlist descriptions
 * while maintaining seamless compatibility with legacy imported playlists stored in Room or Firestore.
 */
object PlaylistMetadataHelper {

    private val TAG_PREFIX_REGEX = Regex("""^\[\[sl_import:([^\]\r\n]+)\]\]\s*""")
    private val LEGACY_IMPORT_REGEX = Regex(
        pattern = """^(?:Импорт|Import|Importazione)\s*\(([^)]+)\)(?:\s*[•·\-]\s*\d+\s*(?:трек(?:а|ов|\(ов\))?|tracks?|brani)?)?\.?\s*(.*)$""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(rawDescription: String?): ParsedPlaylistDescription {
        val raw = rawDescription?.trim().orEmpty()
        if (raw.isEmpty()) {
            return ParsedPlaylistDescription(importSource = null, userDescription = "")
        }
        val tagMatch = TAG_PREFIX_REGEX.find(raw)
        if (tagMatch != null) {
            val source = tagMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
            val userDesc = raw.removeRange(tagMatch.range).trim()
            return ParsedPlaylistDescription(importSource = source, userDescription = userDesc)
        }
        val legacyMatch = LEGACY_IMPORT_REGEX.matchEntire(raw)
        if (legacyMatch != null) {
            val source = legacyMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
            val trailingDesc = legacyMatch.groupValues.getOrNull(2)?.trim().orEmpty()
            return ParsedPlaylistDescription(importSource = source, userDescription = trailingDesc)
        }
        return ParsedPlaylistDescription(importSource = null, userDescription = raw)
    }

    fun encodeImportedDescription(
        sourceLabel: String,
        userDescription: String = "",
    ): String {
        val cleanSource = sourceLabel.trim().replace("]", "").replace("\n", " ")
        val cleanDesc = parse(userDescription).userDescription
        if (cleanSource.isEmpty()) return cleanDesc
        return if (cleanDesc.isEmpty()) {
            "[[sl_import:$cleanSource]]"
        } else {
            "[[sl_import:$cleanSource]]\n$cleanDesc"
        }
    }

    fun mergeUserEdit(
        existingRawDescription: String?,
        newUserDescription: String,
    ): String {
        val existingSource = parse(existingRawDescription).importSource
        val cleanNewDesc = parse(newUserDescription).userDescription
        return if (existingSource != null) {
            encodeImportedDescription(existingSource, cleanNewDesc)
        } else {
            cleanNewDesc
        }
    }
}
