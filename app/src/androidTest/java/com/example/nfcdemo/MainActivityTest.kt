package com.example.nfcdemo

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import java.nio.charset.Charset

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private lateinit var activityScenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        // Launch the activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        activityScenario.close()
    }

    @Test
    fun testInitialState() {
        // Verify initial UI state
        onView(withId(R.id.tvStatus)).check(matches(isDisplayed()))
        onView(withId(R.id.etMessage)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSendMode)).check(matches(isDisplayed()))
        onView(withId(R.id.rvMessages)).check(matches(isDisplayed()))
        
        // Send button should be disabled initially (since message field is empty)
        onView(withId(R.id.btnSendMode)).check(matches(not(isEnabled())))
    }
    
    @Test
    fun testEnteringMessage() {
        // Enter a message
        val testMessage = "Test message"
        onView(withId(R.id.etMessage)).perform(typeText(testMessage), closeSoftKeyboard())
        
        // Verify the message was entered
        onView(withId(R.id.etMessage)).check(matches(withText(testMessage)))
        
        // Send button should be enabled now
        onView(withId(R.id.btnSendMode)).check(matches(isEnabled()))
    }
    
    @Test
    fun testSendButtonClick() {
        // Enter a message
        val testMessage = "Test message for sending"
        onView(withId(R.id.etMessage)).perform(typeText(testMessage), closeSoftKeyboard())
        
        // Get the initial message count
        var initialMessageCount = 0
        activityScenario.onActivity { activity ->
            val recyclerView = activity.findViewById<RecyclerView>(R.id.rvMessages)
            initialMessageCount = recyclerView.adapter?.itemCount ?: 0
        }
        
        // Click the send button
        onView(withId(R.id.btnSendMode)).perform(click())
        
        // Verify the message field is cleared
        onView(withId(R.id.etMessage)).check(matches(withText("")))
        
        // Verify a message was added to the list
        activityScenario.onActivity { activity ->
            val recyclerView = activity.findViewById<RecyclerView>(R.id.rvMessages)
            val newMessageCount = recyclerView.adapter?.itemCount ?: 0
            assertEquals(initialMessageCount + 1, newMessageCount)
        }
        
        // Verify the status text indicates send mode
        onView(withId(R.id.tvStatus)).check(matches(withText(R.string.status_send_mode)))
    }
    
    @Test
    fun testToggleMode() {
        // First, enter and send a message to get into send mode
        val testMessage = "Test message for toggling"
        onView(withId(R.id.etMessage)).perform(typeText(testMessage), closeSoftKeyboard())
        onView(withId(R.id.btnSendMode)).perform(click())
        
        // Verify we're in send mode
        onView(withId(R.id.tvStatus)).check(matches(withText(R.string.status_send_mode)))
        
        // Click the status text to toggle mode
        onView(withId(R.id.tvStatus)).perform(click())
        
        // Verify we're now in receive mode
        onView(withId(R.id.tvStatus)).check(matches(withText(R.string.status_receive_mode)))
        
        // Click again to try to toggle back to send mode (should work since we have a pending message)
        onView(withId(R.id.tvStatus)).perform(click())
        
        // Verify we're back in send mode
        onView(withId(R.id.tvStatus)).check(matches(withText(R.string.status_send_mode)))
    }
    
    @Test
    fun testSettingsButtonClick() {
        // Click the settings button
        onView(withId(R.id.btnSettings)).perform(click())
        
        // Verify the SettingsActivity is launched
        // This is a bit tricky to test directly, but we can check that the current activity is no longer MainActivity
        // or we can check for elements that are unique to SettingsActivity
        
        // For this test, we'll just verify that the settings button click doesn't crash the app
        activityScenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }
    
    @Test
    fun testHandleShareIntent() {
        // Close the current activity
        activityScenario.close()
        
        // Create a share intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shared text for testing")
        }
        
        // Launch the activity with the share intent
        activityScenario = ActivityScenario.launch(shareIntent)
        
        // Verify the shared text is in the message field
        onView(withId(R.id.etMessage)).check(matches(withText("Shared text for testing")))
    }
    
    @Test
    fun testMessageLengthLimit() {
        activityScenario.onActivity { activity ->
            // Set a small message length limit
            activity.setMessageLengthLimit(10)
            
            // Add a long message
            val longMessage = "This is a very long message that should be truncated in the display"
            activity.findViewById<EditText>(R.id.etMessage).setText(longMessage)
            activity.findViewById<LinearLayout>(R.id.btnSendMode).performClick()
            
            // Verify the message was added with truncation
            val recyclerView = activity.findViewById<RecyclerView>(R.id.rvMessages)
            val adapter = recyclerView.adapter as MessageAdapter
            val lastPosition = adapter.itemCount - 1
            val displayText = adapter.getDisplayText(adapter.getItem(lastPosition))
            
            // The display text should be truncated
            assertTrue(displayText.length <= 13) // 10 chars + "..."
            assertTrue(displayText.endsWith("..."))
            
            // But the original content should be intact
            assertEquals(longMessage, adapter.getItem(lastPosition).content)
        }
    }
    
    @Test
    fun testSaveAndAddMessage() {
        activityScenario.onActivity { activity ->
            // Get initial message count
            val recyclerView = activity.findViewById<RecyclerView>(R.id.rvMessages)
            val initialCount = recyclerView.adapter?.itemCount ?: 0
            
            // Add a sent message
            val sentMessage = "Test sent message"
            val sentPosition = activity.saveAndAddMessage(sentMessage, true)
            
            // Verify the message was added
            assertEquals(initialCount + 1, recyclerView.adapter?.itemCount)
            assertTrue(sentPosition >= 0)
            
            // Add a received message
            val receivedMessage = "Test received message"
            val receivedPosition = activity.saveAndAddMessage(receivedMessage, false)
            
            // Verify the message was added
            assertEquals(initialCount + 2, recyclerView.adapter?.itemCount)
            assertTrue(receivedPosition >= 0)
        }
    }
} 