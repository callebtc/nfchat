# Makefile for NFC Demo Android app

# Default task
.PHONY: all
all: build

# Check if gradlew exists and is executable
check-gradlew:
	@if [ ! -x "./gradlew" ]; then \
		echo "Making gradlew executable..."; \
		chmod +x ./gradlew; \
	fi

# Run all unit tests
.PHONY: test
test: check-gradlew
	./gradlew testDebugUnitTest

# Run a specific test class
.PHONY: test-class
test-class: check-gradlew
	@if [ -z "$(CLASS)" ]; then \
		echo "Usage: make test-class CLASS=com.example.nfcdemo.MessageDataTest"; \
		exit 1; \
	fi
	./gradlew testDebugUnitTest --tests "$(CLASS)"

# Clean the project
.PHONY: clean
clean: check-gradlew
	./gradlew clean

# Build the debug APK
.PHONY: build
build: check-gradlew
	./gradlew assembleDebug

# Install the debug APK on a connected device
.PHONY: install
install: check-gradlew
	./gradlew installDebug

# Run lint checks
.PHONY: lint
lint: check-gradlew
	./gradlew lint

# Generate test coverage report
.PHONY: coverage
coverage: check-gradlew
	./gradlew testDebugUnitTestCoverage
	@echo "Coverage report generated at: app/build/reports/coverage/test/debug/index.html"

# Show help
.PHONY: help
help:
	@echo "Available targets:"
	@echo "  make              - Build the debug APK"
	@echo "  make test         - Run all unit tests"
	@echo "  make test-class   - Run a specific test class (e.g., make test-class CLASS=com.example.nfcdemo.MessageDataTest)"
	@echo "  make clean        - Clean the project"
	@echo "  make build        - Build the debug APK"
	@echo "  make install      - Install the debug APK on a connected device"
	@echo "  make lint         - Run lint checks"
	@echo "  make coverage     - Generate test coverage report"
	@echo "  make help         - Show this help message" 