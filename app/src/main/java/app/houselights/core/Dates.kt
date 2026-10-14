package app.houselights.core

private const val YEAR_DIGITS = 4

/** The year of an ISO date ("2021-12-15") as servers and NFO files give it; null when there is none. */
fun yearOf(date: String?): Int? = date?.take(YEAR_DIGITS)?.toIntOrNull()
