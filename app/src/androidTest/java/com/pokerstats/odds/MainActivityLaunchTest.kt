package com.pokerstats.odds

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: the app must actually start. If MainActivity throws during
 * launch, `ActivityScenario.launch` fails and the crash's stack trace shows up
 * in the instrumentation output — turning a "closes instantly" report into a
 * concrete exception.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    @Test
    fun appLaunchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
