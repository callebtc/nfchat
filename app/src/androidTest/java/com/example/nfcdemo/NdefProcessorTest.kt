package com.example.nfcdemo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nfcdemo.nfc.NdefProcessor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NdefProcessorTest {

    private lateinit var processor: NdefProcessor

    @Before
    fun setUp() {
        processor = NdefProcessor()
    }

    @Test
    fun testSelectApplication() {
        // Test SELECT AID command
        val selectAidCommand = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 
            0x07.toByte(), 0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 
            0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte(), 
            0x00.toByte()
        )
        
        val response = processor.processCommandApdu(selectAidCommand)
        
        // Check that we get the OK response (90 00)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
    }
    
    @Test
    fun testSelectNdefFile() {
        // Test SELECT NDEF FILE command
        val selectFileCommand = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(),
            0x02.toByte(), 0xE1.toByte(), 0x03.toByte()
        )
        
        val response = processor.processCommandApdu(selectFileCommand)
        
        // Check that we get the OK response (90 00)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
    }
    
    @Test
    fun testReadBinary() {
        // First, select the NDEF application and file
        testSelectApplication()
        testSelectNdefFile()
        
        // Test READ BINARY command
        val readBinaryCommand = byteArrayOf(
            0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x0F.toByte() // Read 15 bytes
        )
        
        val response = processor.processCommandApdu(readBinaryCommand)
        
        // The response should be the NDEF data (at least 8 bytes) + OK (90 00)
        assertTrue(response.size >= 10) // At least 8 bytes of data + 2 bytes status
        
        // The last two bytes should be 90 00
        assertEquals(0x90.toByte(), response[response.size - 2])
        assertEquals(0x00.toByte(), response[response.size - 1])
        
        // Check the NDEF header structure
        assertEquals(0xE1.toByte(), response[0]) // Magic number
        assertEquals(0x03.toByte(), response[1]) // Version
    }
    
    @Test
    fun testUpdateBinary() {
        // First, select the NDEF application and file
        testSelectApplication()
        testSelectNdefFile()
        
        // Create an NDEF message with a simple text record
        // This is a simplified NDEF message with text "Test"
        // The format is:
        // - NDEF file length (2 bytes): 00 0B
        // - NDEF message length (2 bytes): 00 07
        // - NDEF message:
        //   - Record header: 0xD1 (MB=1, ME=1, SR=1, TNF=001)
        //   - Type length: 0x01
        //   - Payload length: 0x05
        //   - Type: "T" (text)
        //   - Payload: 0x02 (UTF-8, 2-char language code) + "en" + "Test"
        
        // First, update NDEF file length and message length
        val updateLengthCommand = byteArrayOf(
            0x00.toByte(), 0xD6.toByte(), 0x00.toByte(), 0x04.toByte(), // Command + offset 4
            0x04.toByte(), // Length 4
            0x00.toByte(), 0x0B.toByte(), 0x00.toByte(), 0x07.toByte() // Data: NDEF file length (11) and message length (7)
        )
        
        var response = processor.processCommandApdu(updateLengthCommand)
        
        // Check that we get the OK response (90 00)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
        
        // Then, write the NDEF message
        val updateMessageCommand = byteArrayOf(
            0x00.toByte(), 0xD6.toByte(), 0x00.toByte(), 0x08.toByte(), // Command + offset 8
            0x07.toByte(), // Length 7
            0xD1.toByte(), 0x01.toByte(), 0x05.toByte(), 0x54.toByte(), // Record header + type length + payload length + "T"
            0x02.toByte(), 0x65.toByte(), 0x6E.toByte(), // Language info: UTF-8 + "en"
            0x54.toByte(), 0x65.toByte(), 0x73.toByte(), 0x74.toByte() // "Test"
        )
        
        response = processor.processCommandApdu(updateMessageCommand)
        
        // Check that we get the OK response (90 00)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
        
        // Read back the NDEF message to verify
        val readBinaryCommand = byteArrayOf(
            0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x0F.toByte() // Read 15 bytes
        )
        
        response = processor.processCommandApdu(readBinaryCommand)
        
        // Check that we get the data + OK response
        assertTrue(response.size >= 15)
        assertEquals(0x90.toByte(), response[response.size - 2])
        assertEquals(0x00.toByte(), response[response.size - 1])
        
        // Check that our NDEF file and message lengths were properly written
        assertEquals(0x00.toByte(), response[4])
        assertEquals(0x0B.toByte(), response[5])
        assertEquals(0x00.toByte(), response[6])
        assertEquals(0x07.toByte(), response[7])
        
        // Check if the message was properly parsed
        val message = processor.getReceivedMessage()
        assertNotNull("Message should not be null", message)
        assertEquals("Test", message)
    }
    
    @Test
    fun testCompleteNdefTagWrite() {
        // This test simulates a complete NDEF tag write operation with the same
        // sequence of commands that NFC Tools would send
        
        // Step 1: Select NDEF application
        var command = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(), 0xD2.toByte(), 0x76.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte(),
            0x00.toByte()
        )
        var response = processor.processCommandApdu(command)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
        
        // Step 2: Select NDEF file
        command = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(),
            0x02.toByte(), 0xE1.toByte(), 0x03.toByte()
        )
        response = processor.processCommandApdu(command)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
        
        // Step 3: Read NDEF file to get current state
        command = byteArrayOf(
            0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x0F.toByte() // Read 15 bytes
        )
        response = processor.processCommandApdu(command)
        assertTrue(response.size >= 10)
        
        // Step 4: Update NDEF file length (17 bytes) and message length (13 bytes)
        command = byteArrayOf(
            0x00.toByte(), 0xD6.toByte(), 0x00.toByte(), 0x04.toByte(),
            0x04.toByte(),
            0x00.toByte(), 0x11.toByte(), 0x00.toByte(), 0x0D.toByte()
        )
        response = processor.processCommandApdu(command)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
        
        // Step 5: Write NDEF message with "Hello, World!" text
        command = byteArrayOf(
            0x00.toByte(), 0xD6.toByte(), 0x00.toByte(), 0x08.toByte(),
            0x0D.toByte(), // Length 13
            0xD1.toByte(), 0x01.toByte(), 0x0B.toByte(), 0x54.toByte(), // Record header + type length + payload length + "T"
            0x02.toByte(), 0x65.toByte(), 0x6E.toByte(), // Language info: UTF-8 + "en"
            0x48.toByte(), 0x65.toByte(), 0x6C.toByte(), 0x6C.toByte(), 0x6F.toByte(), // "Hello"
            0x2C.toByte(), // ","
            0x20.toByte(), // " "
            0x57.toByte(), 0x6F.toByte(), 0x72.toByte(), 0x6C.toByte(), 0x64.toByte(), // "World"
            0x21.toByte() // "!"
        )
        response = processor.processCommandApdu(command)
        assertEquals(2, response.size)
        assertEquals(0x90.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])
        
        // Verify the message was properly parsed
        val message = processor.getReceivedMessage()
        assertNotNull("Message should not be null", message)
        assertEquals("Hello, World!", message)
    }
} 