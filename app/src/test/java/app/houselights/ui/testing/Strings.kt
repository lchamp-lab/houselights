package app.houselights.ui.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/**
 * The app's string [id] with [args], in the test's language (its `@Config` qualifiers): what a screen says, compared to
 * the copy, so that a copy edit in strings.xml never breaks a test of what the screen does.
 */
fun text(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
