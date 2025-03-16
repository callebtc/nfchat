# Message Handler System

This directory contains the message handler system for the NFC Chat app. The system allows for pluggable message handlers that can process and react to messages in different ways.

## Overview

The message handler system consists of:

1. `MessageHandler` interface - The base interface that all message handlers must implement
2. `MessageHandlerManager` - A singleton manager that registers and manages message handlers
3. Individual handler implementations (e.g., `LinkHandler`, `CashuHandler`)

## How It Works

When a message is received, the `MessageProcessor` passes it through all registered handlers. Each handler can process the message in its own way and perform actions based on the message content.

The system is designed to be extensible, allowing new handlers to be added easily without modifying existing code.

## Existing Handlers

### LinkHandler

The `LinkHandler` detects URLs in messages and opens them in either the internal or external browser, based on user settings.

### CashuHandler

The `CashuHandler` detects Cashu tokens in messages (strings starting with "cashuA..." or "cashuB...") and opens them using either:
- A web URL with a configurable pattern (default: `https://wallet.cashu.me/#token={token}`)
- A dedicated app using the `cashu:` URI scheme

## Creating a New Handler

To create a new message handler:

1. Create a new class that implements the `MessageHandler` interface
2. Implement the `processMessage` method to process messages and perform actions
3. Implement the `isEnabled` method to determine if the handler is enabled based on settings
4. Register the handler in `MainActivity.initializeMessageHandlers()`

Example:

```kotlin
class MyCustomHandler : MessageHandler {
    override fun processMessage(context: Context, message: String, dbHelper: MessageDbHelper): Boolean {
        // Process the message and return true if handled, false otherwise
        return false
    }
    
    override fun isEnabled(dbHelper: MessageDbHelper): Boolean {
        // Check if this handler is enabled based on settings
        return true
    }
}
```

Then register it in `MainActivity`:

```kotlin
private fun initializeMessageHandlers() {
    // Clear any existing handlers
    MessageHandlerManager.clearHandlers()
    
    // Register the handlers
    MessageHandlerManager.registerHandler(LinkHandler())
    MessageHandlerManager.registerHandler(CashuHandler())
    MessageHandlerManager.registerHandler(MyCustomHandler())
    
    Log.d(TAG, "Message handlers initialized")
}
```

## Settings

Each handler can have its own settings in the app's settings screen. To add settings for a new handler:

1. Add setting keys to `SettingsContract`
2. Add default values to `AppConstants`
3. Add UI elements to `activity_settings.xml`
4. Add string resources to `strings.xml`
5. Update `SettingsActivity` to handle the new settings 