package app.houselights.providers.local

import android.util.Log
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.FailureKind
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MetadataEditor
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.NotFoundException
import app.houselights.core.NfoParser
import app.houselights.core.Typeset
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.core.yearOf
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user's choices for the local library. They are stored apart from what scans find
 * (`user*` columns), so scans keep refreshing automatic values without overriding them, and
 * choosing the automatic option again simply clears the choice.
 */
class LocalEditor(
    private val dao: LibraryDao,
    private val scanner: LibraryScanner,
    /** The TMDB client in use, or null without a key. */
    private val tmdb: () -> TmdbClient?,
) : MetadataEditor {

    override fun canEdit(item: MediaItem): Boolean = item.kind == MediaKind.MOVIE || item.kind == MediaKind.SERIES

    override suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices {
        val e = entity(item)
        val tmdb = client()
        val (online, onlineError) = onlineImages(e, tmdb)
        val images = when (kind) {
            ArtworkKind.POSTER -> online?.posters
            ArtworkKind.BACKDROP -> online?.backdrops
            ArtworkKind.LOGO -> online?.logos
        }.orEmpty()
        val known = images.associateBy { it.filePath }
        val language = tmdb?.languageCode ?: java.util.Locale.getDefault().language
        // Backgrounds read best without text; posters and logos in the viewer's language.
        val preference = if (kind == ArtworkKind.BACKDROP) listOf(null, language, "en") else listOf(language, "en", null)
        val ranked = images.sortedWith(
            compareBy<TmdbImage> { preference.indexOf(it.iso6391).let { i -> if (i < 0) preference.size else i } }
                .thenByDescending { it.voteAverage }
        )
        // Up to 1 MB of XML: parsed away from the main thread.
        val nfo = e.nfo?.let { text -> withContext(Dispatchers.Default) { NfoParser.parse(text) } }
        val nfoUrl = when (kind) {
            ArtworkKind.POSTER -> nfo?.posterUrl
            ArtworkKind.BACKDROP -> nfo?.fanartUrl
            ArtworkKind.LOGO -> nfo?.logoUrl
        }

        val automatic = e.automaticValue(kind)
        val current = e.userValue(kind) ?: automatic
        val values = buildList {
            automatic?.let(::add)
            if (kind == ArtworkKind.LOGO) add(NONE)
            nfoUrl?.let(::add)
            ranked.forEach { add(it.filePath) }
        }.distinct().take(MAX_OPTIONS).toMutableList()
        // A choice made from images TMDB no longer lists stays visible next to the automatic one.
        if (current != null && current !in values) values.add(if (automatic != null) 1 else 0, current)

        fun option(value: String): ArtworkOption {
            val image = known[value]
            return ArtworkOption(
                value = value,
                image = value.takeIf { it != NONE }?.let { LocalProvider.image(it, kind.imageType) },
                origin = when {
                    value == NONE -> ArtworkOrigin.NONE
                    value.isCachedArtwork() -> ArtworkOrigin.LOCAL_FILE
                    value.startsWith("http") -> ArtworkOrigin.NFO
                    else -> ArtworkOrigin.ONLINE
                },
                language = image?.iso6391,
                width = image?.width ?: 0,
                height = image?.height ?: 0,
            )
        }
        val options = values.map(::option)
        return ArtworkChoices(
            options = options,
            automatic = automatic?.let { a -> options.first { it.value == a } },
            selected = current?.let { c -> options.first { it.value == c } },
            onlineAvailable = online != null,
            onlineError = onlineError,
        )
    }

    /**
     * TMDB's images of [e], or why they could not be listed: no key, or TMDB's failure, logged with the
     * title. A title not identified has none to list, and nothing failed.
     */
    private suspend fun onlineImages(e: ItemEntity, tmdb: TmdbClient?): Pair<TmdbImageSet?, FailureKind?> {
        val id = e.tmdbId ?: return null to null
        if (tmdb == null) return null to FailureKind.NO_TMDB_KEY
        return suspendRunCatching { tmdb.images(id, e.kind == ItemKind.SERIES) }.fold(
            onSuccess = { it to null },
            onFailure = { failure ->
                Log.w(TAG, "Images of ${e.kind} ${e.id} (TMDB $id) could not be listed", failure)
                null to failure.failureKind()
            },
        )
    }

    override suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption) {
        val e = entity(item)
        // Choosing what automatic matching would use is going back to automatic.
        val value = option.value.takeIf { it != e.automaticValue(kind) }
        when (kind) {
            ArtworkKind.POSTER -> dao.setUserPoster(e.id, value)
            ArtworkKind.BACKDROP -> dao.setUserBackdrop(e.id, value)
            ArtworkKind.LOGO -> dao.setUserLogo(e.id, value)
        }
        scanner.notifyChanged()
    }

    override suspend fun identity(item: MediaItem): Identity {
        val e = entity(item)
        val (query, year, fileName) = when (e.kind) {
            ItemKind.MOVIE -> {
                val path = e.filePath.orEmpty()
                // A film in several files is searched for by its name without the part.
                val parsed = e.filmName() ?: LibraryScanner.parseMovie(path)
                Triple(parsed.title, parsed.year, path)
            }
            else -> {
                val parsed = scanner.showQuery(e)
                val folder = dao.episodesOfSeries(e.id).firstNotNullOfOrNull { it.filePath }?.let(FilenameParser::parseShowPath)?.dir
                Triple(parsed.title, parsed.year, folder?.ifEmpty { null })
            }
        }
        return Identity(
            fileName = fileName,
            query = query,
            year = year,
            current = e.tmdbId?.let {
                MatchCandidate(
                    id = it.toString(),
                    title = Typeset.text(e.title),
                    originalTitle = Typeset.text(e.originalTitle?.takeIf { o -> o != e.title }),
                    year = e.year,
                    overview = Typeset.text(e.overview),
                    poster = (e.userPosterPath ?: e.posterPath)?.let { p -> LocalProvider.image(p, ImageType.POSTER) },
                )
            },
            manual = e.userTmdbId != null,
            canSearch = client() != null,
        )
    }

    override suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate> {
        val tmdb = client() ?: throw NoTmdbKeyException()
        val show = entity(item).kind == ItemKind.SERIES
        val q = query.trim()
        if (IMDB_ID.matches(q)) return listOfNotNull(tmdb.findImdb(q.lowercase(), show)?.toCandidate())
        if (q.isEmpty()) return emptyList()
        suspend fun find(y: Int?) = if (show) tmdb.searchShows(q, y) else tmdb.searchMovies(q, y)
        // The year narrows results, but is often off by one in file names: complete without it.
        val withYear = find(year)
        val results = if (year != null && withYear.size < 5) withYear + find(null) else withYear
        return results.distinctBy { it.id }.take(MAX_RESULTS).map { it.toCandidate() }
    }

    override suspend fun identify(item: MediaItem, candidate: MatchCandidate?) {
        if (client() == null) throw NoTmdbKeyException()
        scanner.identify(entity(item).id, candidate?.id?.toInt())
    }

    private suspend fun entity(item: MediaItem): ItemEntity =
        (LocalId.parse(item.id) as? LocalId.Item)?.let { dao.item(it.key) } ?: throw NotFoundException("Item ${item.id}")

    private fun client(): TmdbClient? = tmdb()

    private fun TmdbSearchResult.toCandidate(): MatchCandidate {
        val name = title ?: name ?: ""
        return MatchCandidate(
            id = id.toString(),
            title = Typeset.text(name),
            originalTitle = Typeset.text((originalTitle ?: originalName)?.takeIf { it != name }),
            year = yearOf(releaseDate ?: firstAirDate),
            overview = Typeset.text(overview?.ifBlank { null }),
            poster = posterPath?.let { LocalProvider.image(it, ImageType.POSTER) },
        )
    }

    private companion object {
        const val TAG = "LocalEditor"

        /** A logo that is no image: the title is set in type. */
        const val NONE = ""
        const val MAX_OPTIONS = 80
        const val MAX_RESULTS = 20
        val IMDB_ID = Regex("(?i)tt\\d{6,9}")

        val ArtworkKind.imageType
            get() = when (this) {
                ArtworkKind.POSTER -> ImageType.POSTER
                ArtworkKind.BACKDROP -> ImageType.BACKDROP
                ArtworkKind.LOGO -> ImageType.LOGO
            }

        /** What scans found; a title without a logo is shown in type, which is an option too. */
        fun ItemEntity.automaticValue(kind: ArtworkKind): String? = when (kind) {
            ArtworkKind.POSTER -> posterPath
            ArtworkKind.BACKDROP -> backdropPath
            ArtworkKind.LOGO -> logoPath ?: NONE
        }

        fun ItemEntity.userValue(kind: ArtworkKind): String? = when (kind) {
            ArtworkKind.POSTER -> userPosterPath
            ArtworkKind.BACKDROP -> userBackdropPath
            ArtworkKind.LOGO -> userLogoPath
        }
    }
}
