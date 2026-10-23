package app.houselights.player

/** The Zidoo's track lists as it answered them on 2026-10-22 (firmware v6.4.68). */
object PlayerAnswers {
    /** Colony, asked for Korean: audio_idx 1. */
    const val COLONY_AUDIO =
        """{"status":200,"currentIndex":1,"size":2,"subtitles":[""" +
            """{"title":"French Dolby Digital 6ch 48,0KHz","laungaue":"fre","isClose":false,"mime":"","channelCount":6,""" +
            """"sampleRate":48000,"index":0},""" +
            """{"title":"Korean Dolby Digital Plus 6ch 48,0KHz","laungaue":"kor","isClose":false,"mime":"","channelCount":6,""" +
            """"sampleRate":48000,"index":1}]}"""

    /** Colony, asked for no subtitles: subtitle_idx 0. */
    const val COLONY_SUBTITLES =
        """{"status":200,"currentIndex":0,"size":6,"subtitles":[""" +
            """{"title":"Désactivé","laungaue":"","subtitleFormat":-1,"isExternal":false,"isClose":true,"index":0},""" +
            """{"title":"French(FR Forced [])-Forced  Internal SRT","laungaue":"fre","subtitleFormat":5,"isExternal":true,""" +
            """"isClose":false,"index":1},""" +
            """{"title":"French(FR [])  Internal SRT","laungaue":"fre","subtitleFormat":5,"isExternal":true,"isClose":false,"index":2},""" +
            """{"title":"French(FR Forced [PGS])-Forced PGS","laungaue":"fre","subtitleFormat":3,"isExternal":false,""" +
            """"isClose":false,"index":3},""" +
            """{"title":"French(FR [PGS]) PGS","laungaue":"fre","subtitleFormat":3,"isExternal":false,"isClose":false,"index":4},""" +
            """{"title":"French(FR (SDH) [PGS]) PGS","laungaue":"fre","subtitleFormat":3,"isExternal":false,"isClose":false,"index":5}]}"""

    /**
     * Colony without its forced French, asked no subtitles: it shows its first, and lists last the file Bazarr put
     * beside the video, by its name (not seen on the Zidoo).
     */
    const val COLONY_BESIDE_SUBTITLES =
        """{"status":200,"currentIndex":1,"size":5,"subtitles":[""" +
            """{"title":"Désactivé","laungaue":"","subtitleFormat":-1,"isExternal":false,"isClose":true,"index":0},""" +
            """{"title":"French(FR [])  Internal SRT","laungaue":"fre","subtitleFormat":5,"isExternal":true,"isClose":false,"index":1},""" +
            """{"title":"French(FR [PGS]) PGS","laungaue":"fre","subtitleFormat":3,"isExternal":false,"isClose":false,"index":2},""" +
            """{"title":"French(FR (SDH) [PGS]) PGS","laungaue":"fre","subtitleFormat":3,"isExternal":false,"isClose":false,"index":3},""" +
            """{"title":"Colony.fr.forced.srt","laungaue":"","subtitleFormat":5,"isExternal":true,"isClose":false,"index":4}]}"""

    /** Either list before the player has read the file, as its code answers it (not seen on the Zidoo). */
    const val NOT_READ_YET = """{"status":200,"currentIndex":0,"size":0,"subtitles":[]}"""
}
