package app.houselights.providers.local

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.houselights.core.Job

/** Number of titles crediting c's person, counting a multi-copy film once (see [CreditDao.creditsOf]). */
private const val TITLES_OF_PERSON =
    "(SELECT COUNT(DISTINCT o.itemId) FROM credits o JOIN items oi ON oi.id = o.itemId " +
        "WHERE o.personKey = c.personKey AND oi.versionOf IS NULL)"

private const val PERSON_SUMMARY =
    "SELECT c.personKey, MIN(c.name) AS name, MAX(c.profilePath) AS profilePath, COUNT(DISTINCT c.itemId) AS titles " +
        "FROM credits c JOIN items i ON i.id = c.itemId"

/** A credit of a title, with the number of library titles crediting that person ([CreditDao.creditsOf]). */
data class TitleCredit(
    val personKey: String,
    val name: String,
    val job: Job,
    val ord: Int,
    val character: String?,
    val profilePath: String?,
    val titles: Int,
)

data class PersonCredit(val itemId: Long, val job: Job, val ord: Int, val name: String, val character: String?, val profilePath: String?)

data class PersonSummary(val personKey: String, val name: String, val profilePath: String?, val titles: Int)

/** A person's TMDB key for a folded name ([CreditDao.tmdbKeysOf]). */
data class NamedKey(val foldedName: String, val personKey: String)

/** The people credited on titles ([LibraryDao] extends it, and keeps the writes that reach other tables). */
@Suppress("TooManyFunctions") // A list of queries, as LibraryDao is.
interface CreditDao {
    @Query("SELECT * FROM credits WHERE itemId = :itemId AND job = 'CAST' ORDER BY ord")
    suspend fun cast(itemId: Long): List<CreditEntity>

    /** Credits of title [itemId] by job in billing order, each with its person's title count. */
    @Query(
        "SELECT c.personKey, c.name, c.job, c.ord, c.character, c.profilePath, $TITLES_OF_PERSON AS titles " +
            "FROM credits c WHERE c.itemId = :itemId ORDER BY c.job, c.ord"
    )
    suspend fun creditsOf(itemId: Long): List<TitleCredit>

    @Query(
        "SELECT c.itemId, c.job, c.ord, c.name, c.character, c.profilePath FROM credits c JOIN items i ON i.id = c.itemId " +
            "WHERE c.personKey = :personKey AND i.versionOf IS NULL"
    )
    suspend fun creditsOfPerson(personKey: String): List<PersonCredit>

    /** People of [job] credited on at least [minTitles] titles of [kind] (any if null), most titles first. */
    @Query(
        "$PERSON_SUMMARY WHERE c.job = :job AND i.versionOf IS NULL AND (:kind IS NULL OR i.kind = :kind) " +
            "GROUP BY c.personKey HAVING COUNT(DISTINCT c.itemId) >= :minTitles ORDER BY titles DESC, name LIMIT :limit"
    )
    suspend fun topPeople(job: Job, kind: ItemKind?, minTitles: Int, limit: Int): List<PersonSummary>

    @Query("$PERSON_SUMMARY WHERE c.personKey IN (:personKeys) AND i.versionOf IS NULL GROUP BY c.personKey")
    suspend fun people(personKeys: List<String>): List<PersonSummary>

    @Query("DELETE FROM credits WHERE itemId = :itemId")
    suspend fun deleteCredits(itemId: Long)

    /** Deletes the credits of [itemId] for [jobs] (enum names). */
    @Query("DELETE FROM credits WHERE itemId = :itemId AND job IN (:jobs)")
    suspend fun deleteCreditsIn(itemId: Long, jobs: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCredits(credits: List<CreditEntity>)

    @Query("SELECT DISTINCT personKey FROM credits WHERE personKey IN (:personKeys)")
    suspend fun heldKeys(personKeys: List<String>): List<String>

    /** TMDB keys already used in credits for [foldedNames]. */
    @Query(
        "SELECT foldedName, MIN(personKey) AS personKey FROM credits WHERE foldedName IN (:foldedNames) " +
            "AND personKey LIKE '${PersonKeys.TMDB}%' GROUP BY foldedName"
    )
    suspend fun tmdbKeysOf(foldedNames: List<String>): List<NamedKey>

    @Query("UPDATE credits SET personKey = :personKey WHERE personKey = :former")
    suspend fun rekeyPerson(former: String, personKey: String)
}
