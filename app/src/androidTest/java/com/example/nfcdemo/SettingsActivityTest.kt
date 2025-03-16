package com.example.nfcdemo

import android.content.Context
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nfcdemo.data.MessageDbHelper
import com.example.nfcdemo.data.SettingsContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {

    private lateinit var activityScenario: ActivityScenario<SettingsActivity>
    private lateinit var dbHelper: MessageDbHelper
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbHelper = MessageDbHelper(context)
        
        // Launch the activity
        activityScenario = ActivityScenario.launch(SettingsActivity::class.java)
    }

    @After
    fun tearDown() {
        activityScenario.close()
        dbHelper.close()
    }

    @Test
    fun testInitialState() {
        // Verify UI elements are displayed
        onView(withId(R.id.cbAutoOpenLinks)).check(matches(isDisplayed()))
        onView(withId(R.id.cbUseInternalBrowser)).check(matches(isDisplayed()))
        onView(withId(R.id.cbAutoSendShared)).check(matches(isDisplayed()))
        onView(withId(R.id.cbCloseAfterSharedSend)).check(matches(isDisplayed()))
        onView(withId(R.id.btnClearMessages)).check(matches(isDisplayed()))
        onView(withId(R.id.btnAdvancedSettings)).check(matches(isDisplayed()))
    }
    
    @Test
    fun testToggleAutoOpenLinks() {
        // Get the initial setting value
        val initialValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, 
            true
        )
        
        // Toggle the switch
        onView(withId(R.id.cbAutoOpenLinks)).perform(click())
        
        // Verify the setting was updated
        val newValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, 
            true
        )
        assertEquals(!initialValue, newValue)
        
        // Toggle back to the original state
        onView(withId(R.id.cbAutoOpenLinks)).perform(click())
        
        // Verify the setting was restored
        val finalValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, 
            true
        )
        assertEquals(initialValue, finalValue)
    }
    
    @Test
    fun testToggleUseInternalBrowser() {
        // Get the initial setting value
        val initialValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
            false
        )
        
        // Toggle the switch
        onView(withId(R.id.cbUseInternalBrowser)).perform(click())
        
        // Verify the setting was updated
        val newValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
            false
        )
        assertEquals(!initialValue, newValue)
        
        // Toggle back to the original state
        onView(withId(R.id.cbUseInternalBrowser)).perform(click())
        
        // Verify the setting was restored
        val finalValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, 
            false
        )
        assertEquals(initialValue, finalValue)
    }
    
    @Test
    fun testToggleAutoSendShared() {
        // Get the initial setting value
        val initialValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED, 
            true
        )
        
        // Toggle the switch
        onView(withId(R.id.cbAutoSendShared)).perform(click())
        
        // Verify the setting was updated
        val newValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED, 
            true
        )
        assertEquals(!initialValue, newValue)
        
        // Toggle back to the original state
        onView(withId(R.id.cbAutoSendShared)).perform(click())
        
        // Verify the setting was restored
        val finalValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_AUTO_SEND_SHARED, 
            true
        )
        assertEquals(initialValue, finalValue)
    }
    
    @Test
    fun testToggleCloseAfterSharedSend() {
        // Get the initial setting value
        val initialValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
            false
        )
        
        // Toggle the switch
        onView(withId(R.id.cbCloseAfterSharedSend)).perform(click())
        
        // Verify the setting was updated
        val newValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
            false
        )
        assertEquals(!initialValue, newValue)
        
        // Toggle back to the original state
        onView(withId(R.id.cbCloseAfterSharedSend)).perform(click())
        
        // Verify the setting was restored
        val finalValue = dbHelper.getBooleanSetting(
            SettingsContract.SettingsEntry.KEY_CLOSE_AFTER_SHARED_SEND, 
            false
        )
        assertEquals(initialValue, finalValue)
    }
    
    @Test
    fun testClearMessagesButton() {
        // Add some test messages to the database
        dbHelper.addMessage("Test message 1", true, false)
        dbHelper.addMessage("Test message 2", false, false)
        
        // Verify messages were added
        val initialCursor = dbHelper.getMessages(10)
        assertTrue(initialCursor.count > 0)
        initialCursor.close()
        
        // Click the clear messages button
        onView(withId(R.id.btnClearMessages)).perform(click())
        
        // We can't easily test the dialog confirmation, but we can verify that
        // clicking the button doesn't crash the app
        activityScenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }
    
    @Test
    fun testAdvancedSettingsButton() {
        // Click the advanced settings button
        onView(withId(R.id.btnAdvancedSettings)).perform(click())
        
        // Verify the advanced settings section is displayed
        onView(withId(R.id.layoutAdvancedSettings)).check(matches(isDisplayed()))
        onView(withId(R.id.etMaxChunkSize)).check(matches(isDisplayed()))
        onView(withId(R.id.etChunkDelay)).check(matches(isDisplayed()))
        onView(withId(R.id.etTransferTimeout)).check(matches(isDisplayed()))
        
        // Click the button again to hide the advanced settings
        onView(withId(R.id.btnAdvancedSettings)).perform(click())
        
        // Verify the advanced settings section is hidden
        onView(withId(R.id.layoutAdvancedSettings)).check(matches(withEffectiveVisibility(Visibility.GONE)))
    }
    
    @Test
    fun testLoadSettings() {
        // Set some test settings
        dbHelper.saveSetting(SettingsContract.SettingsEntry.KEY_AUTO_OPEN_LINKS, "false")
        dbHelper.saveSetting(SettingsContract.SettingsEntry.KEY_USE_INTERNAL_BROWSER, "true")
        
        // Restart the activity to reload settings
        activityScenario.close()
        activityScenario = ActivityScenario.launch(SettingsActivity::class.java)
        
        // Verify the switches reflect the saved settings
        activityScenario.onActivity { activity ->
            val switchAutoOpenLinks = activity.findViewById<CheckBox>(R.id.cbAutoOpenLinks)
            val switchUseInternalBrowser = activity.findViewById<CheckBox>(R.id.cbUseInternalBrowser)
            
            assertFalse(switchAutoOpenLinks.isChecked)
            assertTrue(switchUseInternalBrowser.isChecked)
        }
    }
} 