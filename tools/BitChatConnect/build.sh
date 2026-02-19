#!/bin/bash
# Build BitChat Connect menu bar app
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="BitChat Connect"
APP_DIR="$SCRIPT_DIR/$APP_NAME.app"
CONTENTS="$APP_DIR/Contents"

echo "Building $APP_NAME..."

# Clean previous build
rm -rf "$APP_DIR"

# Create .app bundle structure
mkdir -p "$CONTENTS/MacOS"
mkdir -p "$CONTENTS/Resources"

# Copy Info.plist
cp "$SCRIPT_DIR/Info.plist" "$CONTENTS/"

# Compile
swiftc \
    "$SCRIPT_DIR/BitChatConnect.swift" \
    -o "$CONTENTS/MacOS/BitChatConnect" \
    -framework Cocoa \
    -framework SwiftUI \
    -target arm64-apple-macosx13.0 \
    -parse-as-library

echo "Build complete: $APP_DIR"
echo ""

# Ask to install to Applications
read -p "Install to /Applications? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    cp -R "$APP_DIR" "/Applications/"
    echo "Installed to /Applications/$APP_NAME.app"
    echo "You can now find it in Spotlight or Launchpad."
fi

echo ""
echo "To run: open \"$APP_DIR\""
