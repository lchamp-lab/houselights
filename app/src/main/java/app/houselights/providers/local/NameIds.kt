package app.houselights.providers.local

import app.houselights.core.NfoInfo

/**
 * The ids a file or folder name gives, as Plex, Jellyfin, Emby and Infuse read them and Radarr and Sonarr write them
 * (TRaSH Guides): "{tmdb-438631}", "[tmdbid-438631]", "[tmdbid=438631]", "{imdb-tt1160419}", "[imdbid-tt1160419]",
 * "{tvdb-78804}", "[tvdbid-78804]", in braces, brackets or parentheses. A name giving two ids of one kind gives none of
 * that kind, as which is meant cannot be told. They identify a title before its NFO's ids and the search, once trusted
 * ([trusts]).
 */
internal data class NameIds(val tmdb: Int? = null, val imdb: String? = null, val tvdb: Int? = null) {
    /**
     * The id a lookup tries first, as [ItemEntity.hintKey] keeps it: "tmdb:438631", else for a show "tvdb:78804", else
     * "imdb:tt1160419"; null without one. TMDB finds no film by its TVDB id.
     */
    fun key(show: Boolean): String? = tmdbKey ?: tvdbKey?.takeIf { show } ?: imdbKey

    /** Each id as [key] gives it. */
    val tmdbKey: String? get() = tmdb?.let { "$TMDB:$it" }
    val imdbKey: String? get() = imdb?.let { "$IMDB:$it" }
    val tvdbKey: String? get() = tvdb?.let { "$TVDB:$it" }

    /**
     * Whether a title its name gives these ids to is looked up by them: never over the user's match, nor when the
     * name's TMDB id is its own; else once they give another id than its last lookup tried ([ItemEntity.hintKey]), at
     * once when [renamed] or never matched, else a day after that lookup. One that tried them, whatever they found, is
     * not asked by them again: a title they found none for is asked again with the others to identify ([Reidentify]).
     */
    fun dueFor(row: Hinted, show: Boolean, now: Long, renamed: Boolean): Boolean {
        val key = key(show) ?: return false
        val own = tmdb != null && tmdb == row.tmdbId
        if (row.userTmdbId != null || key == row.hintKey || own) return false
        val matchedAt = row.matchedAt ?: return true
        return renamed || now - matchedAt >= RETRY_MS
    }

    /**
     * Whether [found], the title found by the id of kind [by] these ids hold, is the title [wanted] names: always when
     * another of these ids or of [nfo] names it too ([TitleIds.agree]); else never when the year of each of its
     * readings is known and more than one from [found]'s, a remake's or a sequel's id ([MatchScore.near]); else when one
     * of its titles is alike one of the readings near it, or failing them one of its alternative titles (one TMDB
     * request), a same-year neighbour's id being neither. An id not trusted gives way to the next, as one TMDB has no
     * title for.
     */
    suspend fun trusts(found: Found, by: IdKind, nfo: NfoInfo?, wanted: Wanted): Boolean {
        if (found.ids.agree(this, by, nfo)) return true
        val near = MatchScore.near(wanted, found.year) ?: return false
        return MatchScore.alike(near, found.titles) || MatchScore.alike(near, found.alternatives())
    }

    /** A title an id of a name found ([trusts]): its ids, titles and year, and its [alternatives] once asked for. */
    class Found(val ids: TitleIds, val titles: List<String>, val year: Int?, val alternatives: suspend () -> List<String>)

    /** A row's columns [dueFor] reads. */
    class Hinted(val tmdbId: Int?, val userTmdbId: Int?, val hintKey: String?, val matchedAt: Long?)

    companion object {
        private const val TMDB = "tmdb"
        private const val IMDB = "imdb"
        private const val TVDB = "tvdb"

        // Android's ICU engine needs closing brackets, braces and parentheses escaped, unlike the JVM.
        val HINT = Regex("""(?i)[\[\{\(]\s*(tmdb|imdb|tvdb)(?:id)?\s*[-=]\s*(tt\d{6,10}|\d{1,9})\s*[\]\}\)]""")

        /** How long a title whose name's id found no title, or another one, waits before it is asked by it again. */
        const val RETRY_MS = 24 * 60 * 60_000L

        /**
         * Titles looked up in a pass for their name's id alone ([dueFor]), as every title named with one is after an
         * upgrade: the others wait for the next passes.
         */
        const val PER_PASS = 40

        /** The ids [name] gives. */
        fun of(name: String): NameIds {
            val ids = HINT.findAll(name).map { it.groupValues[1].lowercase() to it.groupValues[2].lowercase() }.toList()
            fun one(kind: String, valid: (String) -> Boolean) = ids.filter { it.first == kind && valid(it.second) }
                .map { it.second }.distinct().singleOrNull()
            val number = { value: String -> value.toIntOrNull()?.let { it > 0 } == true }
            return NameIds(
                tmdb = one(TMDB, number)?.toInt(),
                imdb = one(IMDB) { it.startsWith("tt") },
                tvdb = one(TVDB, number)?.toInt(),
            )
        }

        /**
         * A film's ids, at [path] it is named by ([Stack.namedPath]; a disc by its folder): its own name's, then for each
         * it lacks its folder's when that folder holds this film alone, its copies and parts ([single], from
         * [ScanTitles.holdingOneFilm]): a folder's id given to two films would group them as copies of one ([GROUP_VERSIONS]).
         */
        fun ofFilm(path: String, single: Set<String>): NameIds {
            val film = filmPath(path)
            val own = of(film.substringAfterLast('/'))
            val folder = Sidecars.folderOf(film)
            return if (folder.isEmpty() || folder !in single) own else own.or(of(folder.substringAfterLast('/')))
        }

        /** [path] as a film is named by it: a disc's stream by the folder holding the disc. */
        fun filmPath(path: String): String = FilenameParser.discOf(path)?.root?.ifEmpty { null } ?: path

        /** A show's ids, its folder's ([ShowPath.dir]); at the source root, its episode file's. */
        fun ofShow(episodePath: String): NameIds {
            val show = FilenameParser.parseShowPath(episodePath) ?: return NameIds()
            return of(show.dir.ifEmpty { episodePath }.substringAfterLast('/'))
        }

        private fun NameIds.or(other: NameIds) = NameIds(tmdb ?: other.tmdb, imdb ?: other.imdb, tvdb ?: other.tvdb)
    }
}

/** The kinds of ids a name gives ([NameIds]), by which a lookup's step found a title ([IdStep]). */
internal enum class IdKind { TMDB, IMDB, TVDB }

/**
 * The ids a title TMDB found has: its own, and the IMDb and TheTVDB ids TMDB knows it by ([TmdbMovie.imdbId],
 * [TmdbTv.externalIds]), the id it was found by among them.
 */
internal data class TitleIds(val tmdb: Int, val imdb: String?, val tvdb: Int?) {
    /** Whether an id of [names] other than the one of kind [by] that found this title, or one of [nfo]'s, names it too. */
    fun agree(names: NameIds, by: IdKind, nfo: NfoInfo?): Boolean {
        val tmdbIds = listOfNotNull(names.tmdb.takeIf { by != IdKind.TMDB }, nfo?.tmdbId)
        val imdbIds = listOfNotNull(names.imdb.takeIf { by != IdKind.IMDB }, nfo?.imdbId)
        val tvdbIds = listOfNotNull(names.tvdb.takeIf { by != IdKind.TVDB }, nfo?.tvdbId?.trim()?.toIntOrNull())
        return tmdb in tmdbIds || imdbIds.any { it.equals(imdb, ignoreCase = true) } || tvdb != null && tvdb in tvdbIds
    }
}

internal val ItemEntity.hinted: NameIds.Hinted get() = NameIds.Hinted(tmdbId, userTmdbId, hintKey, matchedAt)

internal val KnownFile.hinted: NameIds.Hinted get() = NameIds.Hinted(tmdbId, userTmdbId, hintKey, matchedAt)
