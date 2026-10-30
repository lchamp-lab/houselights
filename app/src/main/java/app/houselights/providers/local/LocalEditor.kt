package app.houselights.providers.local

import android.util.Log
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkNote
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.EpisodeOrderChoices
import app.houselights.core.EpisodeOrderKind
import app.houselights.core.EpisodeOrderOption
import app.houselights.core.FailureKind
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MetadataEditor
import app.houselights.core.NfoParser
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.NotFoundException
import app.houselights.core.ToIdentify
import app.houselights.core.Typeset
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.core.yearOf
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User edits for the local library. They live in separate `user*` columns, so scans keep refreshing automatic
 * values without overriding them, and picking the automatic option again clears the choice.
 */
class LocalEditor(
    private val dao: LibraryDao,
    private val scanner: LibraryScanner,
    /** Current TMDB client; null without a key. */
    private val tmdb: () -> TmdbClient?,
) : MetadataEditor {

    /** A film, a show, and a saga (#48), whose poster and backdrop alone are chosen: TMDB gives a collection no logo. */
    override fun canEdit(item: MediaItem): Boolean =
        item.kind == MediaKind.MOVIE || item.kind == MediaKind.SERIES || item.kind == MediaKind.COLLECTION

    override suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices {
        sagaKey(item)?.let { return sagaArtwork(it, kind) }
        val e = entity(item)
        val tmdb = client()
        val (online, onlineError) = onlineImages(e, tmdb)
        val images = online.of(kind)
        val known = images.associateBy { it.filePath }
        val ranked = ranked(images, kind, language = tmdb?.languageCode ?: java.util.Locale.getDefault().language)
        val nfoUrl = nfoUrl(e, kind)

        val automatic = e.automaticValue(kind)
        val current = e.userValue(kind) ?: automatic
        val values = buildList {
            automatic?.let(::add)
            if (kind == ArtworkKind.LOGO) add(NONE)
            nfoUrl?.let(::add)
            ranked.forEach { add(it.filePath) }
        }.distinct().take(MAX_OPTIONS).toMutableList()
        // A chosen image missing from TMDB's list stays visible next to the automatic one.
        if (current != null && current !in values) values.add(if (automatic != null) 1 else 0, current)

        val options = values.map { option(it, kind, known[it]) }
        return ArtworkChoices(
            options = options,
            automatic = automatic?.let { a -> options.first { it.value == a } },
            selected = current?.let { c -> options.first { it.value == c } },
            onlineAvailable = online != null,
            onlineError = onlineError,
        )
    }

    /** TMDB's images of [kind]; none without them. */
    private fun TmdbImageSet?.of(kind: ArtworkKind): List<TmdbImage> = when (kind) {
        ArtworkKind.POSTER -> this?.posters
        ArtworkKind.BACKDROP -> this?.backdrops
        ArtworkKind.LOGO -> this?.logos
    }.orEmpty()

    /**
     * [images] in the order they are offered: by [language] as [kind] prefers it, then by TMDB's votes, the
     * [PER_LANGUAGE] best rated of each language, so that one language never fills the [MAX_OPTIONS] of the choice and
     * each keeps its section on the contact sheet (« la planche-contact »).
     */
    private fun ranked(images: List<TmdbImage>, kind: ArtworkKind, language: String): List<TmdbImage> {
        // Backdrops work best without text; posters and logos prefer the viewer's language.
        val preference = if (kind == ArtworkKind.BACKDROP) listOf(null, language, "en") else listOf(language, "en", null)
        return images.sortedWith(
            compareBy<TmdbImage> { preference.indexOf(it.iso6391).let { i -> if (i < 0) preference.size else i } }
                .thenByDescending { it.voteAverage },
        ).groupBy { it.iso6391 }.values.flatMap { it.take(PER_LANGUAGE) }
    }

    /** The picture of [kind] the title's NFO names, if any. */
    private suspend fun nfoUrl(e: ItemEntity, kind: ArtworkKind): String? {
        // Up to 1 MB of XML, so parse off the main thread.
        val nfo = e.nfo?.let { text -> withContext(Dispatchers.Default) { NfoParser.parse(text) } }
        return when (kind) {
            ArtworkKind.POSTER -> nfo?.posterUrl
            ArtworkKind.BACKDROP -> nfo?.fanartUrl
            ArtworkKind.LOGO -> nfo?.logoUrl
        }
    }

    /** The choice of [value], with what TMDB says of its [image] when it is one of TMDB's. */
    private fun option(value: String, kind: ArtworkKind, image: TmdbImage?) = ArtworkOption(
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

    /**
     * TMDB images of [e], or why they are unavailable: no key, or a TMDB failure (logged). An unidentified title
     * gives no images and no failure.
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

    /**
     * A saga's choices of [kind] (#48), its poster or its backdrop: Automatique, today's rule (TMDB's picture of the saga,
     * else its first film's), and the one in use; then TMDB's pictures of the saga, by language as a film's, the
     * [PER_LANGUAGE] best of each; then the one each film held shows (its choice, else its own), in release order, named
     * by its title and year ([ArtworkOrigin.FILM]), a picture two films show once, never cut by the [MAX_OPTIONS] TMDB's
     * share of which they leave. A saga an NFO's set names and TMDB does not know has its films' alone
     * ([ArtworkNote.SAGA_UNKNOWN]).
     */
    private suspend fun sagaArtwork(key: Long, kind: ArtworkKind): ArtworkChoices {
        val c = sagaOf(key, kind)
        val films = dao.collectionFilms(c.id)
        val automatic = c.automatic(kind, films.firstOrNull())
        val current = dao.sagaChoice(c.matchKey)?.picture(kind) ?: automatic
        // The film that first shows each picture, in release order.
        val byFilm = LinkedHashMap<String, ItemEntity>()
        films.forEach { film -> film.shownPicture(kind)?.let { byFilm.putIfAbsent(it, film) } }
        val tmdb = client()
        val (online, onlineError) = sagaImages(c, tmdb)
        val images = online.of(kind)
        val known = images.associateBy { it.filePath }
        val head = listOfNotNull(automatic)
        val fromFilms = byFilm.keys.filter { it !in head }
        val ranked = ranked(images, kind, language = tmdb?.languageCode ?: java.util.Locale.getDefault().language)
            .map { it.filePath }
            .filter { it !in head && it !in byFilm }
            .distinct()
            .take((MAX_OPTIONS - head.size - fromFilms.size).coerceAtLeast(0))
        val values = (head + ranked + fromFilms).toMutableList()
        // A chosen picture missing from the lists stays visible next to the automatic one.
        if (current != null && current !in values) values.add(head.size, current)
        val options = values.map { value ->
            val option = option(value, kind, known[value])
            val film = byFilm[value] ?: return@map option
            option.copy(origin = ArtworkOrigin.FILM, film = Typeset.text(film.title), filmYear = film.year)
        }
        return ArtworkChoices(
            options = options,
            automatic = automatic?.let { a -> options.first { it.value == a } },
            selected = current?.let { s -> options.first { it.value == s } },
            onlineAvailable = online != null,
            onlineError = onlineError,
            note = if (c.tmdbId == null) ArtworkNote.SAGA_UNKNOWN else ArtworkNote.SAGA,
        )
    }

    /** TMDB's pictures of saga [c], or why they are missing: no key, or a TMDB failure (logged); none for one it does not know. */
    private suspend fun sagaImages(c: CollectionEntity, tmdb: TmdbClient?): Pair<TmdbImageSet?, FailureKind?> {
        val id = c.tmdbId ?: return null to null
        if (tmdb == null) return null to FailureKind.NO_TMDB_KEY
        return suspendRunCatching { tmdb.collectionImages(id) }.fold(
            onSuccess = { it to null },
            onFailure = { failure ->
                Log.w(TAG, "Images of saga ${c.id} (TMDB $id) could not be listed", failure)
                null to failure.failureKind()
            },
        )
    }

    /**
     * Keeps saga [key]'s choice of [kind] by its key, never on its row, so scans and the saga leaving with its last film
     * and coming back keep it; Automatique clears it, and the row goes once both its pictures are automatic.
     */
    private suspend fun setSagaArtwork(key: Long, kind: ArtworkKind, option: ArtworkOption) {
        val c = sagaOf(key, kind)
        val value = option.value.takeIf { it != c.automatic(kind, dao.collectionFilms(c.id).firstOrNull()) }
        val was = dao.sagaChoice(c.matchKey) ?: SagaChoiceEntity(c.matchKey)
        val now = if (kind == ArtworkKind.POSTER) was.copy(userPosterPath = value) else was.copy(userBackdropPath = value)
        if (now.userPosterPath == null && now.userBackdropPath == null) dao.deleteSagaChoice(c.matchKey) else dao.saveSagaChoice(now)
        scanner.notifyChanged()
    }

    /** Saga [key], whose [kind] of picture is chosen: its poster or its backdrop, TMDB giving a collection no logo. */
    private suspend fun sagaOf(key: Long, kind: ArtworkKind): CollectionEntity {
        if (kind == ArtworkKind.LOGO) throw NotFoundException("A saga has no logo to choose")
        return dao.collection(key) ?: throw NotFoundException("Collection $key")
    }

    /** The saga [item] is, by its row's key; null for a film or a show. */
    private fun sagaKey(item: MediaItem): Long? = (LocalId.parse(item.id) as? LocalId.Collection)?.key

    override suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption) {
        sagaKey(item)?.let { return setSagaArtwork(it, kind, option) }
        val e = entity(item)
        // Choosing the automatic value clears the user choice.
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
                // A multi-file film is searched by its name without the part suffix.
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
            doubt = e.doubt(),
            kept = e.userNoMatch && e.tmdbId == null,
            suggestions = if (e.doubt() == MatchDoubt.UNSURE) dao.suggestions(e.id).map { it.toCandidate() } else emptyList(),
        )
    }

    override suspend fun nextToIdentify(item: MediaItem): String? {
        val e = entity(item)
        return dao.nextToIdentify(e.addedAt, e.id)?.toString()
    }

    override suspend fun toIdentify(): List<ToIdentify> {
        val rows = dao.toIdentify()
        if (rows.isEmpty()) return emptyList()
        val folders = dao.sources().associate { it.id to it.name }
        val shows = rows.filter { it.kind == ItemKind.SERIES }.map { it.id }
        val showFiles = shows.chunked(SQL_IN_LIMIT).flatMap { dao.showFiles(it) }.associate { it.seriesId to it.filePath }
        val offered = rows.map { it.id }.chunked(SQL_IN_LIMIT).flatMap { dao.suggestionsOf(it) }.groupBy { it.itemId }
        // Each title and suggestion typeset, each show's path parsed: off the main thread, as the catalog's.
        return withContext(Dispatchers.Default) {
            rows.mapNotNull { e ->
                val doubt = e.doubt() ?: return@mapNotNull null
                // A film's file, or a show's folder, under the folder's name: "Films/Dune.mkv".
                val within = if (e.kind == ItemKind.SERIES) showFiles[e.id]?.let(FilenameParser::parseShowPath)?.dir else e.filePath
                val place = listOfNotNull(folders[e.sourceId], within?.ifEmpty { null }).joinToString("/").ifEmpty { null }
                ToIdentify(e.listed(doubt), place, doubt, offered[e.id].orEmpty().map { it.toCandidate() })
            }
        }
    }

    /**
     * Written at once, not under the scanner's lock: a check asking TMDB about the title meanwhile then writes nothing
     * of what it found ([Reidentify]), where waiting for it would let its search match the title first.
     */
    override suspend fun keepUnidentified(item: MediaItem) {
        val e = entity(item)
        if (e.kind != ItemKind.MOVIE && e.kind != ItemKind.SERIES) return
        dao.setUserNoMatch(e.id, true)
        scanner.notifyChanged()
    }

    override suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate> {
        val tmdb = client() ?: throw NoTmdbKeyException()
        val show = entity(item).kind == ItemKind.SERIES
        val q = query.trim()
        if (IMDB_ID.matches(q)) return listOfNotNull(tmdb.findImdb(q.lowercase(), show)?.toCandidate())
        if (q.isEmpty()) return emptyList()
        suspend fun find(y: Int?) = if (show) tmdb.searchShows(q, y) else tmdb.searchMovies(q, y)
        // File-name years are often off by one, so few results are completed by a search without the year.
        val withYear = find(year)
        val results = if (year != null && withYear.size < FEW_RESULTS) withYear + find(null) else withYear
        return results.distinctBy { it.id }.take(MAX_RESULTS).map { it.toCandidate() }
    }

    override suspend fun identify(item: MediaItem, candidate: MatchCandidate?) {
        if (client() == null) throw NoTmdbKeyException()
        scanner.identify(entity(item).id, candidate?.id?.toInt())
    }

    /**
     * A show's orders, from the library alone ([EpisodeOrderEntity]), as its scans listed them: TMDB's seasons, then its
     * groups in TMDB's order; none until a group is known, so the choice never opens on nothing. Automatique follows the
     * group its tvshow.nfo names ([NfoOrders]), else TMDB's seasons.
     */
    override suspend fun episodeOrders(item: MediaItem): EpisodeOrderChoices? {
        val e = entity(item).takeIf { it.kind == ItemKind.SERIES } ?: return null
        val rows = dao.episodeOrders(e.tmdbId ?: return null)
        val groups = rows.filter { it.orderId != EpisodeOrderEntity.AIRED && it.places != EpisodeOrders.REFUSED }
        if (groups.none { it.kind() != null }) return null
        val counted = rows.firstOrNull { it.orderId == EpisodeOrderEntity.AIRED }?.places
        val seasons = OrderPlaces.aired(counted).orEmpty().filter { it.n > 0 }
        val aired = EpisodeOrderOption(
            EpisodeOrderEntity.AIRED,
            EpisodeOrderKind.AIRED,
            episodes = seasons.sumOf { it.c },
            seasons = seasons.size,
        )
        val options = listOf(aired) + groups.mapNotNull { g ->
            g.kind()?.let { EpisodeOrderOption(g.orderId, it, Typeset.text(g.name.ifBlank { null }), g.episodeCount) }
        }
        // Up to 1 MB of XML, so parse off the main thread.
        val nfo = e.nfo?.let { text -> withContext(Dispatchers.Default) { NfoParser.parse(text) } }
        val named = NfoOrders.groupOf(nfo, groups)?.let { g -> options.firstOrNull { it.id == g.orderId } }
        val chosen = e.userEpisodeOrder?.takeIf { c -> options.any { it.id == c } }
        return EpisodeOrderChoices(options, chosen, automatic = named ?: aired, fromNfo = named != null)
    }

    override suspend fun chooseEpisodeOrder(item: MediaItem, id: String?) {
        if (client() == null) throw NoTmdbKeyException()
        scanner.chooseOrder(entity(item).id, id)
    }

    private suspend fun entity(item: MediaItem): ItemEntity =
        (LocalId.parse(item.id) as? LocalId.Item)?.let { dao.item(it.key) } ?: throw NotFoundException("Item ${item.id}")

    private fun client(): TmdbClient? = tmdb()

    /** A title to identify as its list shows it: its name, year and poster, and why ([doubt]). */
    private fun ItemEntity.listed(doubt: MatchDoubt) = MediaItem(
        id = id.toString(),
        kind = if (kind == ItemKind.SERIES) MediaKind.SERIES else MediaKind.MOVIE,
        title = Typeset.text(title),
        year = year,
        poster = (userPosterPath ?: posterPath)?.let { LocalProvider.image(it, ImageType.POSTER) },
        matchDoubt = doubt,
    )

    /** A candidate automatic matching kept ([SuggestionEntity]), as a search would give it. */
    private fun SuggestionEntity.toCandidate() = MatchCandidate(
        id = tmdbId.toString(),
        title = Typeset.text(title),
        originalTitle = Typeset.text(originalTitle?.takeIf { it != title }),
        year = year,
        overview = Typeset.text(overview?.ifBlank { null }),
        poster = posterPath?.let { LocalProvider.image(it, ImageType.POSTER) },
    )

    private fun TmdbSearchResult.toCandidate(): MatchCandidate {
        val name = (title ?: name).orEmpty()
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

        /** The "no logo" option: the title is typeset instead. */
        const val NONE = ""
        const val MAX_OPTIONS = 80

        /** The pictures of one language offered at most: five lines of posters, six of backdrops on the contact sheet. */
        const val PER_LANGUAGE = 30
        const val MAX_RESULTS = 20

        /** Fewer results than this for a year are completed by a search without it. */
        const val FEW_RESULTS = 5
        val IMDB_ID = Regex("(?i)tt\\d{6,9}")

        val ArtworkKind.imageType
            get() = when (this) {
                ArtworkKind.POSTER -> ImageType.POSTER
                ArtworkKind.BACKDROP -> ImageType.BACKDROP
                ArtworkKind.LOGO -> ImageType.LOGO
            }

        /** TMDB's types of episode groups, as their kinds. */
        val GROUP_KINDS = mapOf(
            1 to EpisodeOrderKind.ORIGINAL_AIR_DATE,
            EpisodeOrderEntity.ABSOLUTE_TYPE to EpisodeOrderKind.ABSOLUTE,
            3 to EpisodeOrderKind.DVD,
            4 to EpisodeOrderKind.DIGITAL,
            EpisodeOrderEntity.STORY_ARC_TYPE to EpisodeOrderKind.STORY_ARC,
            6 to EpisodeOrderKind.PRODUCTION,
            7 to EpisodeOrderKind.TV,
        )

        /** The kind of a group's TMDB type ([EpisodeOrderEntity.type]); null for one this version does not know. */
        fun EpisodeOrderEntity.kind(): EpisodeOrderKind? = GROUP_KINDS[type]

        /** The scanned value; a missing logo means [NONE], itself a selectable option. */
        fun ItemEntity.automaticValue(kind: ArtworkKind): String? = when (kind) {
            ArtworkKind.POSTER -> posterPath
            ArtworkKind.BACKDROP -> backdropPath
            ArtworkKind.LOGO -> logoPath ?: NONE
        }

        /** A film's picture of [kind] as it shows it: the user's choice, else its own. */
        fun ItemEntity.shownPicture(kind: ArtworkKind): String? = when (kind) {
            ArtworkKind.POSTER -> LocalProvider.shown(userPosterPath, posterPath)
            ArtworkKind.BACKDROP -> LocalProvider.shown(userBackdropPath, backdropPath)
            ArtworkKind.LOGO -> LocalProvider.shown(userLogoPath, logoPath)
        }

        /**
         * A saga's Automatique of [kind], its poster or its backdrop, as its walls and its page show it without a choice
         * ([LocalProvider]): TMDB's picture of it, else its [first] film's.
         */
        fun CollectionEntity.automatic(kind: ArtworkKind, first: ItemEntity?): String? =
            (if (kind == ArtworkKind.POSTER) posterPath else backdropPath) ?: first?.shownPicture(kind)

        /** A saga's poster or backdrop ([kind]) as the user chose it; null for Automatique. */
        fun SagaChoiceEntity.picture(kind: ArtworkKind): String? = if (kind == ArtworkKind.POSTER) userPosterPath else userBackdropPath

        fun ItemEntity.userValue(kind: ArtworkKind): String? = when (kind) {
            ArtworkKind.POSTER -> userPosterPath
            ArtworkKind.BACKDROP -> userBackdropPath
            ArtworkKind.LOGO -> userLogoPath
        }
    }
}
