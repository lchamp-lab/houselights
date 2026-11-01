package app.houselights.providers.local

import android.content.Context
import androidx.core.content.edit

/**
 * The version of the matching rules ([MatchPlan.RULES]) the library's titles to identify were last asked by, kept
 * beside the library rather than in it: rules newer than it ask them all again ([Reidentify.newRules]).
 */
interface RulesMark {
    /** 0 when none is kept. */
    fun read(): Int

    fun write(version: Int)

    /** In memory, from [version]: the rules in use unless told otherwise. */
    class Kept(private var version: Int = MatchPlan.RULES) : RulesMark {
        override fun read() = version

        override fun write(version: Int) {
            this.version = version
        }
    }

    /** In the app's preferences; lost with the app's data, as the library is. */
    class Saved(context: Context) : RulesMark {
        private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

        override fun read() = prefs.getInt(KEY, 0)

        /** Written at once; not written, the titles to identify are asked again at the next pass, once more. */
        override fun write(version: Int) = prefs.edit(commit = true) { putInt(KEY, version) }

        private companion object {
            const val KEY = "match_rules"
        }
    }
}
