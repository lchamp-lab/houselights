package app.houselights.providers.local

import app.houselights.core.BadgeTokens
import app.houselights.core.Codecs
import app.houselights.core.Definition
import app.houselights.core.FormatFamily
import app.houselights.core.FormatMark
import app.houselights.core.Formats
import app.houselights.core.HdrFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/** File-name badges use the same tokens as probed files: one spelling each, or a filter misses some titles. */
class BadgeTokensTest {
    private val names = listOf(
        "Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1-GRP.mkv",
        "Heat.1995.2160p.HDR10+.DTS-HD.MA.5.1-GRP.mkv",
        "Ronin.1998.1080p.HDR.DTS-X-GRP.mkv",
        "Collateral.2004.720p.mkv",
    )

    @Test
    fun everyBadgeANameGivesIsAKnownToken() {
        val known = Definition.entries.map { it.token } + HdrFormat.entries.map { it.token } +
            listOf(BadgeTokens.HDR, BadgeTokens.ATMOS, BadgeTokens.DTS_X, BadgeTokens.REMUX, Codecs.TRUEHD, Codecs.DTS_HD_MA)
        val badges = names.flatMap(FilenameParser::badges)
        assertEquals(emptyList<String>(), badges.filterNot { it in known })
        // Each badge a name can give besides its definition, so that none is left untried.
        assertEquals(
            setOf(
                HdrFormat.DOLBY_VISION.token,
                HdrFormat.HDR10_PLUS.token,
                BadgeTokens.HDR,
                Codecs.TRUEHD,
                BadgeTokens.ATMOS,
                Codecs.DTS_HD_MA,
                BadgeTokens.DTS_X,
                BadgeTokens.REMUX,
            ),
            badges.toSet() - Definition.entries.map { it.token }.toSet(),
        )
    }

    @Test
    fun aPageReadsANamesBadgesAsTheFormatsTheyAre() {
        assertEquals(
            listOf(
                FormatMark("Ultra HD", FormatFamily.PICTURE),
                FormatMark("Dolby Vision", FormatFamily.PICTURE),
                FormatMark("Dolby TrueHD Atmos", FormatFamily.SOUND),
            ),
            Formats.fromBadges(FilenameParser.badges(names[0])),
        )
        assertEquals(
            listOf(
                FormatMark("Full HD", FormatFamily.PICTURE),
                FormatMark("HDR", FormatFamily.PICTURE),
                FormatMark("DTS:X", FormatFamily.SOUND),
            ),
            Formats.fromBadges(FilenameParser.badges(names[2])),
        )
    }
}
