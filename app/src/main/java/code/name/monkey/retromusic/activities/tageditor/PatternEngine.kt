package code.name.monkey.retromusic.activities.tageditor

import java.io.File

data class TagFields(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val track: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val discNumber: String? = null,
    val comment: String? = null,
    val duration: String? = null
)

object PatternEngine {

    private val placeholders = listOf(
        "%title%", "%artist%", "%album%", "%track%", "%year%", "%genre%",
        "%albumArtist%", "%composer%", "%disc%", "%comment%"
    )

    /**
     * Renombra un archivo o genera una sugerencia de nombre basada en etiquetas.
     * Ejemplo: "%track% - %artist% - %title%" -> "01 - Soda Stereo - De Musica Ligera"
     */
    fun tagsToFilename(pattern: String, tags: TagFields): String {
        var result = pattern
        result = result.replace("%title%", tags.title ?: "")
        result = result.replace("%artist%", tags.artist ?: "")
        result = result.replace("%album%", tags.album ?: "")
        result = result.replace("%track%", tags.track ?: "")
        result = result.replace("%year%", tags.year ?: "")
        result = result.replace("%genre%", tags.genre ?: "")
        result = result.replace("%albumArtist%", tags.albumArtist ?: "")
        result = result.replace("%composer%", tags.composer ?: "")
        result = result.replace("%disc%", tags.discNumber ?: "")
        result = result.replace("%comment%", tags.comment ?: "")

        // Sanitizar caracteres no permitidos en sistemas de archivos
        return result.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /**
     * Extrae etiquetas desde un nombre de archivo basado en un patrón.
     * Ejemplo: "%track% - %artist% - %title%", "01 - Soda Stereo - De Musica Ligera"
     */
    fun filenameToTags(pattern: String, filename: String): TagFields? {
        val baseName = splitExtension(filename).first
        
        // Escapar caracteres especiales del patrón para Regex, excepto los placeholders
        var regexString = pattern
            .replace(".", "\\.")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("+", "\\+")
            
        val foundPlaceholders = mutableListOf<String>()
        val matches = Regex("%\\w+%").findAll(pattern)
        for (match in matches) {
            foundPlaceholders.add(match.value)
        }

        for (placeholder in placeholders) {
            regexString = regexString.replace(placeholder, "(.*)")
        }

        val regex = Regex("^$regexString$")
        val matchResult = regex.find(baseName) ?: return null

        val values = matchResult.groupValues.drop(1)
        if (values.size != foundPlaceholders.size) return null

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track: String? = null
        var year: String? = null
        var genre: String? = null
        var albumArtist: String? = null
        var composer: String? = null
        var disc: String? = null
        var comment: String? = null

        foundPlaceholders.forEachIndexed { index, p ->
            val value = values[index].trim()
            when (p) {
                "%title%" -> title = value
                "%artist%" -> artist = value
                "%album%" -> album = value
                "%track%" -> track = value
                "%year%" -> year = value
                "%genre%" -> genre = value
                "%albumArtist%" -> albumArtist = value
                "%composer%" -> composer = value
                "%disc%" -> disc = value
                "%comment%" -> comment = value
            }
        }

        return TagFields(title, artist, album, track, year, genre, albumArtist, composer, disc, comment)
    }

    fun splitExtension(filename: String): Pair<String, String> {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot == -1) {
            Pair(filename, "")
        } else {
            Pair(filename.substring(0, lastDot), filename.substring(lastDot))
        }
    }
}
