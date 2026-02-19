#!/bin/bash
#
# BitChat Connect - One-Step Installer
# Run this on your LOCAL Mac (the one with the emulator)
#
# Usage: bash install.sh
#
set -e

echo ""
echo "  ╔═══════════════════════════════╗"
echo "  ║   BitChat Connect Installer   ║"
echo "  ╚═══════════════════════════════╝"
echo ""

INSTALL_DIR="$HOME/.bitchat-connect"
APP_DIR="/Applications/BitChat Connect.app"

mkdir -p "$INSTALL_DIR"

# ---------- Write the main script ----------
cat > "$INSTALL_DIR/bitchat-connect.sh" << 'MAINSCRIPT'
#!/bin/bash
#
# BitChat Connect - Menu Bar Controller
# Manages ADB tunnel to remote build server
#

ADB="$HOME/Library/Android/sdk/platform-tools/adb"
REMOTE="local_server@server.local"
PID_FILE="$HOME/.bitchat-connect/ssh.pid"
LOG_FILE="$HOME/.bitchat-connect/connect.log"

log() {
    echo "$(date '+%H:%M:%S') $1" >> "$LOG_FILE"
}

notify() {
    osascript -e "display notification \"$2\" with title \"$1\"" 2>/dev/null
}

get_status() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        # Check if device is actually connected
        DEVS=$("$ADB" devices 2>/dev/null | grep -c "device$" || true)
        if [ "$DEVS" -gt 0 ]; then
            echo "connected"
        else
            echo "tunnel_only"
        fi
    else
        echo "disconnected"
    fi
}

do_connect() {
    log "=== Connect requested ==="

    # Kill any existing tunnel
    do_disconnect_quiet

    # Step 1: Restart local ADB
    log "Restarting local ADB server..."
    "$ADB" kill-server 2>/dev/null || true
    sleep 1
    "$ADB" start-server 2>/dev/null
    sleep 1

    # Step 2: Check for emulator
    DEVS=$("$ADB" devices 2>/dev/null | grep -v "List" | grep -v "^$" || true)
    if [ -z "$DEVS" ]; then
        log "No emulator found"
        notify "BitChat Connect" "No emulator found. Start it in Android Studio first!"
        echo "no_emulator"
        return
    fi
    log "Emulator found: $DEVS"

    # Step 3: SSH tunnel (background)
    ssh -f -N \
        -o "ExitOnForwardFailure=yes" \
        -o "ServerAliveInterval=30" \
        -o "ServerAliveCountMax=3" \
        -o "ConnectTimeout=10" \
        -R 5037:localhost:5037 \
        "$REMOTE" 2>> "$LOG_FILE"

    # Save the PID
    SSH_PID=$(pgrep -f "ssh.*-R 5037:localhost:5037.*$REMOTE" | head -1)
    if [ -n "$SSH_PID" ]; then
        echo "$SSH_PID" > "$PID_FILE"
        log "SSH tunnel started (PID $SSH_PID)"
        notify "BitChat Connect" "Connected! Emulator is ready for remote builds."
        echo "connected"
    else
        log "Failed to start SSH tunnel"
        notify "BitChat Connect" "Failed to connect. Check your network."
        echo "error"
    fi
}

do_disconnect() {
    do_disconnect_quiet
    notify "BitChat Connect" "Disconnected from remote server."
}

do_disconnect_quiet() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        kill "$PID" 2>/dev/null || true
        rm -f "$PID_FILE"
        log "Tunnel stopped (PID $PID)"
    fi
    # Kill any lingering tunnels
    pkill -f "ssh.*-R 5037:localhost:5037.*$REMOTE" 2>/dev/null || true
}

do_status() {
    STATUS=$(get_status)
    case "$STATUS" in
        connected)
            DEV=$("$ADB" devices 2>/dev/null | grep "device$" | head -1 | cut -f1)
            echo "Connected|$DEV"
            ;;
        tunnel_only)
            echo "Tunnel Active|No device"
            ;;
        disconnected)
            echo "Disconnected|--"
            ;;
    esac
}

# Command dispatch
case "${1:-status}" in
    connect)    do_connect ;;
    disconnect) do_disconnect ;;
    status)     do_status ;;
    log)        tail -20 "$LOG_FILE" 2>/dev/null ;;
    *)          echo "Usage: $0 {connect|disconnect|status|log}" ;;
esac
MAINSCRIPT

chmod +x "$INSTALL_DIR/bitchat-connect.sh"

# ---------- Write the menu bar app (AppleScript) ----------
cat > "$INSTALL_DIR/menubar.scpt" << 'APPLESCRIPT'
use framework "Foundation"
use framework "AppKit"
use scripting additions

property menuRunning : true
property isConnected : false
property statusItem : missing value
property theMenu : missing value

on run
    -- Create status bar item
    set statusBar to current application's NSStatusBar's systemStatusBar()
    set my statusItem to statusBar's statusItemWithLength:(current application's NSVariableStatusItemLength)

    -- Set initial icon
    set button to statusItem's button()
    button's setTitle:"📡 Off"

    -- Create menu
    set my theMenu to current application's NSMenu's alloc()'s init()

    -- Status item (disabled, just for display)
    set statusMenuItem to current application's NSMenuItem's alloc()'s initWithTitle:"Status: Disconnected" action:(missing value) keyEquivalent:""
    statusMenuItem's setEnabled:false
    theMenu's addItem:statusMenuItem

    -- Device item
    set deviceMenuItem to current application's NSMenuItem's alloc()'s initWithTitle:"Device: --" action:(missing value) keyEquivalent:""
    deviceMenuItem's setEnabled:false
    theMenu's addItem:deviceMenuItem

    theMenu's addItem:(current application's NSMenuItem's separatorItem())

    -- Connect button
    set connectItem to current application's NSMenuItem's alloc()'s initWithTitle:"Connect" action:"connectAction:" keyEquivalent:"c"
    connectItem's setTarget:me
    theMenu's addItem:connectItem

    -- Disconnect button
    set disconnectItem to current application's NSMenuItem's alloc()'s initWithTitle:"Disconnect" action:"disconnectAction:" keyEquivalent:"d"
    disconnectItem's setTarget:me
    theMenu's addItem:disconnectItem

    theMenu's addItem:(current application's NSMenuItem's separatorItem())

    -- View log
    set logItem to current application's NSMenuItem's alloc()'s initWithTitle:"View Log..." action:"viewLogAction:" keyEquivalent:"l"
    logItem's setTarget:me
    theMenu's addItem:logItem

    theMenu's addItem:(current application's NSMenuItem's separatorItem())

    -- Quit
    set quitItem to current application's NSMenuItem's alloc()'s initWithTitle:"Quit" action:"quitAction:" keyEquivalent:"q"
    quitItem's setTarget:me
    theMenu's addItem:quitItem

    statusItem's setMenu:theMenu

    -- Start status polling timer (every 10 seconds)
    current application's NSTimer's scheduledTimerWithTimeInterval:10 target:me selector:"pollStatus:" userInfo:(missing value) repeats:true

    -- Initial status check
    my pollStatus:missing value
end run

on connectAction:sender
    set button to statusItem's button()
    button's setTitle:"📡 ..."

    try
        do shell script "$HOME/.bitchat-connect/bitchat-connect.sh connect"
    end try

    delay 2
    my pollStatus:missing value
end connectAction:

on disconnectAction:sender
    try
        do shell script "$HOME/.bitchat-connect/bitchat-connect.sh disconnect"
    end try
    delay 1
    my pollStatus:missing value
end disconnectAction:

on viewLogAction:sender
    try
        do shell script "open -a TextEdit $HOME/.bitchat-connect/connect.log"
    end try
end viewLogAction:

on quitAction:sender
    try
        do shell script "$HOME/.bitchat-connect/bitchat-connect.sh disconnect" & " 2>/dev/null &"
    end try
    current application's NSApp's terminate:me
end quitAction:

on pollStatus:timer
    try
        set statusOutput to do shell script "$HOME/.bitchat-connect/bitchat-connect.sh status"
        set AppleScript's text item delimiters to "|"
        set statusParts to text items of statusOutput
        set statusText to item 1 of statusParts
        set deviceText to item 2 of statusParts

        set button to statusItem's button()

        if statusText starts with "Connected" then
            button's setTitle:"📡 ✓"
            set my isConnected to true
        else if statusText starts with "Tunnel" then
            button's setTitle:"📡 ~"
            set my isConnected to false
        else
            button's setTitle:"📡 Off"
            set my isConnected to false
        end if

        -- Update menu items
        (theMenu's itemAtIndex:0)'s setTitle:("Status: " & statusText)
        (theMenu's itemAtIndex:1)'s setTitle:("Device: " & deviceText)
    end try
end pollStatus:
APPLESCRIPT

# ---------- Create the .app bundle ----------
echo "[1/3] Creating app bundle..."

rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/Contents/MacOS"
mkdir -p "$APP_DIR/Contents/Resources"

# Info.plist
cat > "$APP_DIR/Contents/Info.plist" << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>launch.sh</string>
    <key>CFBundleIdentifier</key>
    <string>com.bitchat.connect</string>
    <key>CFBundleName</key>
    <string>BitChat Connect</string>
    <key>CFBundleDisplayName</key>
    <string>BitChat Connect</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSUIElement</key>
    <true/>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
</dict>
</plist>
PLIST

# Launcher script
cat > "$APP_DIR/Contents/MacOS/launch.sh" << 'LAUNCHER'
#!/bin/bash
DIR="$HOME/.bitchat-connect"
/usr/bin/osascript "$DIR/menubar.scpt" &
# Keep the app "running" so macOS doesn't kill it
wait
LAUNCHER
chmod +x "$APP_DIR/Contents/MacOS/launch.sh"

echo "[2/3] App installed to /Applications/BitChat Connect.app"

# ---------- Add to Login Items (optional) ----------
echo "[3/3] Setup complete!"
echo ""
echo "  ✅ BitChat Connect installed!"
echo ""
echo "  To use:"
echo "    1. Start your emulator in Android Studio"
echo "    2. Open 'BitChat Connect' from Applications / Spotlight"
echo "    3. Click the 📡 icon in your menu bar"
echo "    4. Click 'Connect'"
echo ""
echo "  The 📡 icon shows:"
echo "    📡 Off  = Not connected"
echo "    📡 ...  = Connecting"
echo "    📡 ✓    = Connected & emulator ready"
echo "    📡 ~    = Tunnel active, no emulator"
echo ""

read -p "  Add to Login Items (auto-start on boot)? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    osascript -e 'tell application "System Events" to make login item at end with properties {path:"/Applications/BitChat Connect.app", hidden:true}' 2>/dev/null
    echo "  Added to Login Items."
fi

echo ""
echo "  Opening BitChat Connect now..."
open "/Applications/BitChat Connect.app"
