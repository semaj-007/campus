package com.example.campus.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.campus.MainActivity
import com.example.campus.data.CampusRepository
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryFlowTest {
    private fun findText(view: View, label: String): TextView? {
        if (view is TextView && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val child = findText(view.getChildAt(i), label)
            if (child != null) return child
        }
        return null
    }
    private fun <T> withRepository(context: Context, block: (CampusRepository) -> T): T {
        val repo = CampusRepository(context)
        return try { block(repo) } finally { repo.close() }
    }
    @Test fun deliveryWizardHandoffAndRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("campus_ui", Context.MODE_PRIVATE).edit()
            .putString("language", "en").putBoolean("demo_session", false).commit()
        withRepository(context) { repo ->
            repo.updatePreferences(repo.preferences.copy(language = "en", darkMode = false, biometricLock = false))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Explore the demo")).perform(scrollTo(), click())
            onView(withText("Your eco impact")).check(matches(isDisplayed()))
            onView(withContentDescription("+  Create request")).perform(scrollTo(), click())
            onView(withText("New delivery request")).check(matches(isDisplayed()))
            onView(withText("Next")).perform(scrollTo(), click())
            onView(withContentDescription("Item description")).perform(replaceText("UI smoke textbook"))
            closeSoftKeyboard()
            onView(withText("Next")).perform(scrollTo(), click())
            onView(withText("Post request")).perform(scrollTo(), click())
            // Select the first matching route. There can be multiple local couriers.
            scenario.onActivity { activity ->
                findText(activity.window.decorView, "Choose courier")!!.performClick()
            }
            onView(withText("Confirm pickup")).perform(scrollTo(), click())
            val delivery = withRepository(context) { repo ->
                repo.deliveries().first { it.description == "UI smoke textbook" && it.status == "IN_TRANSIT" }
            }
            onView(withText("Confirm handoff")).perform(scrollTo(), click())
            onView(withContentDescription("Verification code")).perform(replaceText(delivery.handoffCode))
            closeSoftKeyboard()
            onView(allOf(withId(android.R.id.button1), withText("Confirm handoff"))).perform(click())
            onView(withText("Delivery complete")).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText("Delivery complete")).perform(scrollTo()).check(matches(isDisplayed()))
            withRepository(context) { repo ->
                assertEquals("COMPLETED", repo.delivery(delivery.id).status)
                assertTrue(repo.profile.ecoScore > 0)
            }
        }
    }
}
