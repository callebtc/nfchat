package com.example.nfcdemo

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.Charset
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4::class)
class CardEmulationServiceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private var dataReceivedFlag = false
    private var receivedData = ""
    private var chunkProgress = Pair(0, 0)
    private var chunkError = ""

    @Before
    fun setUp() {
        try {
            // Start the service
            val serviceIntent = Intent(
                ApplicationProvider.getApplicationContext(),
                CardEmulationService::class.java
            )
            serviceRule.startService(serviceIntent)
            
            // Wait for the service to be created
            Thread.sleep(500)
            
            // Get the service instance
            val service = CardEmulationService.instance
            assertNotNull("Service instance should not be null", service)
            
            // Reset test flags
            dataReceivedFlag = false
            receivedData = ""
            chunkProgress = Pair(0, 0)
            chunkError = ""
            
            // Set up listeners
            service?.onDataReceivedListener = { data ->
                dataReceivedFlag = true
                receivedData = data.content
            }
            
            service?.onChunkProgressListener = { received, total ->
                chunkProgress = Pair(received, total)
            }
            
            service?.onChunkErrorListener = { error ->
                chunkError = error
            }
        } catch (e: TimeoutException) {
            fail("Timed out while starting CardEmulationService")
        }
    }

    @Test
    fun testProcessCommandSelectAid() {
        // Create a SELECT AID command
        val selectApdu = byteArrayOf(
            0x00.toByte(), // CLA
            0xA4.toByte(), // INS
            0x04.toByte(), // P1
            0x00.toByte(), // P2
            0x07.toByte(), // Lc (length of AID)
            0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 
            0x04.toByte(), 0x05.toByte(), 0x06.toByte(), // AID: F0010203040506
            0x00.toByte() // Le
        )
        
        // Process the command
        val response = CardEmulationService.instance?.processCommandApdu(selectApdu, null)
        
        // Verify the response (should be SELECT_OK_SW: 9000)
        assertNotNull(response)
        response?.let {
            assertEquals(2, it.size)
            assertEquals(0x90.toByte(), it[0])
            assertEquals(0x00.toByte(), it[1])
        }
    }
    
    @Test
    fun testProcessCommandGetData() {
        // Set a message to share
        val testMessage = "Test message"
        CardEmulationService.instance?.messageToShare = testMessage
        
        // Create a GET_DATA command
        val getDataCommand = "GET_DATA".toByteArray(Charset.forName("UTF-8"))
        
        // Process the command
        val response = CardEmulationService.instance?.processCommandApdu(getDataCommand, null)
        
        // Verify the response
        assertNotNull(response)
        response?.let {
            assertTrue(it.size > 2) // Should contain data + status word
            
            // Extract the data (remove the status word)
            val dataBytes = it.copyOfRange(0, it.size - 2)
            val responseMessage = String(dataBytes, Charset.forName("UTF-8"))
            
            // Verify the message
            assertEquals(testMessage, responseMessage)
            
            // Verify the status word (should be SELECT_OK_SW: 9000)
            assertEquals(0x90.toByte(), it[it.size - 2])
            assertEquals(0x00.toByte(), it[it.size - 1])
        }
    }
    
    @Test
    fun testProcessCommandSendData() {
        // Create a SEND_DATA command with a test message
        val testMessage = "{\"content\":\"Test message\",\"id\":\"test-id-123\"}"
        val sendDataCommand = "SEND_DATA:$testMessage".toByteArray(Charset.forName("UTF-8"))
        
        // Process the command
        val response = CardEmulationService.instance?.processCommandApdu(sendDataCommand, null)
        
        // Verify the response (should be SELECT_OK_SW: 9000)
        assertNotNull(response)
        response?.let {
            assertEquals(2, it.size)
            assertEquals(0x90.toByte(), it[0])
            assertEquals(0x00.toByte(), it[1])
        }
        
        // Verify the data received callback was triggered
        assertTrue(dataReceivedFlag)
        assertEquals(testMessage, receivedData)
    }
    
    @Test
    fun testChunkedMessageInitialization() {
        // Create a CHUNK_INIT command
        val totalLength = 1000
        val chunkSize = 200
        val totalChunks = 5
        val chunkInitCommand = "CHUNK_INIT:$totalLength:$chunkSize:$totalChunks".toByteArray(Charset.forName("UTF-8"))
        
        // Process the command
        val response = CardEmulationService.instance?.processCommandApdu(chunkInitCommand, null)
        
        // Verify the response (should be SELECT_OK_SW: 9000)
        assertNotNull(response)
        response?.let {
            assertEquals(2, it.size)
            assertEquals(0x90.toByte(), it[0])
            assertEquals(0x00.toByte(), it[1])
        }
        
        // Verify the service is now in chunked receive mode
        assertTrue(CardEmulationService.instance?.isReceivingChunkedMessage() == true)
    }
    
    @Test
    fun testChunkedMessageDataReceive() {
        // First initialize chunked transfer
        val totalLength = 20
        val chunkSize = 10
        val totalChunks = 2
        val chunkInitCommand = "CHUNK_INIT:$totalLength:$chunkSize:$totalChunks".toByteArray(Charset.forName("UTF-8"))
        CardEmulationService.instance?.processCommandApdu(chunkInitCommand, null)
        
        // Send first chunk
        val chunk1 = "First chun"
        val chunkDataCommand1 = "CHUNK_DATA:0:$chunk1".toByteArray(Charset.forName("UTF-8"))
        val response1 = CardEmulationService.instance?.processCommandApdu(chunkDataCommand1, null)
        
        // Verify the response contains the acknowledgment
        val responseStr1 = if (response1 != null) {
            String(response1.copyOfRange(0, response1.size - 2), Charset.forName("UTF-8"))
        } else {
            ""
        }
        assertTrue(responseStr1.startsWith("CHUNK_ACK:0"))
        
        // Send second chunk
        val chunk2 = "k message"
        val chunkDataCommand2 = "CHUNK_DATA:1:$chunk2".toByteArray(Charset.forName("UTF-8"))
        val response2 = CardEmulationService.instance?.processCommandApdu(chunkDataCommand2, null)
        
        // Verify the response contains the acknowledgment
        val responseStr2 = if (response2 != null) {
            String(response2.copyOfRange(0, response2.size - 2), Charset.forName("UTF-8"))
        } else {
            ""
        }
        assertTrue(responseStr2.startsWith("CHUNK_ACK:1"))
        
        // Complete the transfer
        val completeCommand = "CHUNK_COMPLETE".toByteArray(Charset.forName("UTF-8"))
        CardEmulationService.instance?.processCommandApdu(completeCommand, null)
        
        // Verify the data received callback was triggered with the complete message
        assertTrue(dataReceivedFlag)
        assertEquals("$chunk1$chunk2", receivedData)
        
        // Verify the service is no longer in chunked receive mode
        assertFalse(CardEmulationService.instance?.isReceivingChunkedMessage() == true)
    }
    
    @Test
    fun testInvalidCommand() {
        // Create an invalid command
        val invalidCommand = "INVALID_COMMAND".toByteArray(Charset.forName("UTF-8"))
        
        // Process the command
        val response = CardEmulationService.instance?.processCommandApdu(invalidCommand, null)
        
        // Verify the response (should be UNKNOWN_CMD_SW: 0000)
        assertNotNull(response)
        response?.let {
            assertEquals(2, it.size)
            assertEquals(0x00.toByte(), it[0])
            assertEquals(0x00.toByte(), it[1])
        }
    }
    
    @Test
    fun testResetChunkedMessageState() {
        // First initialize chunked transfer
        val chunkInitCommand = "CHUNK_INIT:100:20:5".toByteArray(Charset.forName("UTF-8"))
        CardEmulationService.instance?.processCommandApdu(chunkInitCommand, null)
        
        // Verify the service is in chunked receive mode
        assertTrue(CardEmulationService.instance?.isReceivingChunkedMessage() == true)
        
        // Reset the chunked message state
        CardEmulationService.instance?.resetChunkedMessageState()
        
        // Verify the service is no longer in chunked receive mode
        assertFalse(CardEmulationService.instance?.isReceivingChunkedMessage() == true)
    }
} 