# NFC NDEF Bridge Implementation

This document explains the integration between the `TransferManager`, `NdefProcessor`, and `CardEmulationService` to enable seamless message exchange using both regular APDU commands and NDEF format.

## Overview

The implementation creates a bridge that allows:

1. When in send mode, the message to be sent is also written as an NDEF message
2. When in receive mode, incoming NDEF messages (via UPDATE BINARY) are processed and forwarded to the UI just like regular messages

## Components

### NdefProcessor

The `NdefProcessor` class has been enhanced with:

- `messageToSend`: A field to store the message to be sent via NDEF
- `isInWriteMode`: A flag to indicate if the processor is in write mode
- `onNdefMessageReceived`: A callback that is triggered when an NDEF message is received
- `setMessageToSend()`: Method to update the message to send
- `setWriteMode()`: Method to toggle write mode
- Enhanced `handleUpdateBinary()`: Now processes incoming NDEF messages and triggers callbacks
- `processReceivedNdefMessage()`: New method to parse NDEF messages and extract text content

### CardEmulationService

The `CardEmulationService` has been modified to:

- Expose the `ndefProcessor` instance (changed from private to public)
- Forward the `messageToShare` to the `ndefProcessor` when updated
- Set up callbacks to process received NDEF messages

### TransferManager

The `TransferManager` now:

- Maintains its own reference to `NdefProcessor` for local operations
- Calls `setupNdefDataReceiver()` to configure the bridge with `CardEmulationService`
- Updates the `ndefProcessor` when switching between send and receive modes
- Forwards messages from the `NdefProcessor` to the UI using the existing callbacks

## Message Flow

### Send Mode

1. When `setLastSentMessage()` is called, the message is also set in the `ndefProcessor`
2. When `switchToSendMode()` is called, the `ndefProcessor` is put in write mode
3. The `ndefProcessor` creates an NDEF message with the content when READ BINARY is received

### Receive Mode

1. When a device writes an NDEF message (via UPDATE BINARY), the `ndefProcessor` extracts the content
2. The `onNdefMessageReceived` callback is triggered with the extracted message
3. The `TransferManager` receives this message and forwards it to the UI using the existing callback chain

## Usage

No API changes are needed. The implementation maintains backward compatibility while adding new functionality:

- Regular NFC message exchange continues to work as before
- NDEF-formatted messages are now also supported
- Messages sent in NDEF format are received and processed like regular messages

## Implementation Details

The bridge is established when:

1. The app starts and initializes the `TransferManager`
2. The `CardEmulationService` is created
3. The `TransferManager` calls `setupNdefDataReceiver()` to connect to the `CardEmulationService.ndefProcessor`

This approach ensures that messages can be exchanged in both formats without requiring changes to the UI or user experience. 