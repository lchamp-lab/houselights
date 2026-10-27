package app.houselights.core

import java.time.Instant
import java.time.LocalDate

private const val YEAR_DIGITS = 4

/** The year of an ISO date ("2021-12-15") as servers and NFO files give it; null when there is none. */
fun yearOf(date: String?): Int? = date?.take(YEAR_DIGITS)?.toIntOrNull()

/** An ISO instant ("2024-05-01T12:34:56.0000000Z", as Jellyfin gives it) in epoch ms; null when it is not one. */
fun epochMillisOf(instant: String?): Long? = instant?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** An ISO date ("2026-12-15", as TMDB dates a film) as a date; null when there is none or it is not one. */
fun dateOf(date: String?): LocalDate? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
