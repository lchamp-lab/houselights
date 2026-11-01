package app.houselights.providers.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The matching rules the titles to identify were asked by, kept between starts ([RulesMark.Saved]). */
@RunWith(RobolectricTestRunner::class)
class RulesMarkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** None kept, from an install before the rules had a version, or a first start: older than any. */
    @Test
    fun theRulesKeptAreReadAtTheNextStart() {
        assertEquals(0, RulesMark.Saved(context).read())
        RulesMark.Saved(context).write(MatchPlan.RULES)
        assertEquals(MatchPlan.RULES, RulesMark.Saved(context).read())
    }
}
