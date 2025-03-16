# NFC Demo App

An Android application for demonstrating NFC communication between devices.

## Features

- Send and receive messages via NFC
- Support for chunked message transfer for large messages
- Message history with SQLite database storage
- Auto-open links in received messages
- Clipboard integration
- Settings for customizing app behavior

## Development

### Prerequisites

- Android Studio Arctic Fox or newer
- JDK 11 or newer
- Android device with NFC support for testing

### Building the App

You can build the app using either Android Studio or the provided Makefile:

```bash
# Build the debug APK
make build

# Install the debug APK on a connected device
make install
```

### Running Tests

The project includes unit tests that can be run using the Makefile:

```bash
# Run all unit tests
make test

# Run a specific test class
make test-class CLASS=com.example.nfcdemo.MessageDataTest

# Generate test coverage report
make coverage
```

After running the coverage task, you can find the HTML report at:
`app/build/reports/coverage/test/debug/index.html`

### Available Makefile Commands

Run `make help` to see all available commands:

```
Available targets:
  make              - Build the debug APK
  make test         - Run all unit tests
  make test-class   - Run a specific test class
  make clean        - Clean the project
  make build        - Build the debug APK
  make install      - Install the debug APK on a connected device
  make lint         - Run lint checks
  make coverage     - Generate test coverage report
  make help         - Show this help message
```

## Continuous Integration

This project uses GitHub Actions for continuous integration. The workflow automatically runs all unit tests and generates coverage reports on:
- Every push to the main branch
- Every pull request to the main branch

The workflow configuration can be found in `.github/workflows/android-tests.yml`.

### CI Artifacts

After each CI run, the following artifacts are available:
- **Test Reports**: Detailed reports of all test executions
- **Coverage Reports**: Code coverage analysis showing which parts of the code are tested

These artifacts can be downloaded from the GitHub Actions workflow run page.

### CI Status

[![Android Tests](https://github.com/yourusername/NFCDemo/actions/workflows/android-tests.yml/badge.svg)](https://github.com/yourusername/NFCDemo/actions/workflows/android-tests.yml)

## License

[MIT License](LICENSE) 