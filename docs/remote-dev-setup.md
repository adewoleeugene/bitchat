# Remote Emulator Development Setup

## How It Works
Your Android emulator runs on your **local Mac** (with the screen).
Code builds on the **remote Mac** (192.168.1.194) via SSH.
An SSH tunnel connects them so the remote machine can deploy to your local emulator.

## One-Time Setup (on your LOCAL Mac)

### 1. Create the connect script

Open **Terminal** on your local Mac and paste this:

```bash
cat > ~/connect-bitchat.sh << 'SCRIPT'
#!/bin/bash
echo "=== BitChat Remote Dev Connect ==="
echo ""

# Path to ADB
ADB=~/Library/Android/sdk/platform-tools/adb

# Step 1: Restart ADB locally
echo "[1/3] Restarting local ADB server..."
$ADB kill-server 2>/dev/null
sleep 1
$ADB start-server
sleep 1

# Step 2: Check emulator is running
echo "[2/3] Checking for emulator..."
DEVICES=$($ADB devices | grep -v "List" | grep -v "^$")
if [ -z "$DEVICES" ]; then
    echo ""
    echo "  No emulator found!"
    echo "  Please start the emulator in Android Studio first."
    echo "  (Tools > Device Manager > click the play button)"
    echo ""
    read -p "  Press Enter after starting the emulator..."
    DEVICES=$($ADB devices | grep -v "List" | grep -v "^$")
    if [ -z "$DEVICES" ]; then
        echo "  Still no emulator. Exiting."
        exit 1
    fi
fi
echo "  Found: $DEVICES"

# Step 3: Connect via SSH with tunnel
echo "[3/3] Connecting to remote server with ADB tunnel..."
echo ""
echo "  Connected! You can now build from the remote machine."
echo "  Keep this window open while developing."
echo "  Press Ctrl+C to disconnect."
echo ""
ssh -R 5037:localhost:5037 local_server@server.local
SCRIPT
chmod +x ~/connect-bitchat.sh
echo "Script created at ~/connect-bitchat.sh"
```

### 2. (Optional) Make it double-clickable

In **Terminal** on your local Mac:

```bash
cat > ~/Desktop/BitChat-Connect.command << 'CMD'
#!/bin/bash
cd ~
./connect-bitchat.sh
CMD
chmod +x ~/Desktop/BitChat-Connect.command
echo "Desktop shortcut created!"
```

This puts a **double-clickable icon on your Desktop**.

## Every Time You Develop

1. **Start the emulator** in Android Studio (play button in Device Manager)
2. **Double-click** `BitChat-Connect.command` on your Desktop (or run `~/connect-bitchat.sh` in Terminal)
3. **Keep that Terminal window open** while developing
4. On the remote machine, verify with: `~/Library/Android/sdk/platform-tools/adb devices`

## Troubleshooting

### "No devices" on remote machine
- Make sure the connect script Terminal window is still open
- Make sure the emulator is running on your local Mac
- Re-run the connect script

### "Address already in use" error
On the **remote Mac**, kill the old ADB server:
```bash
~/Library/Android/sdk/platform-tools/adb kill-server
```
Then re-run the connect script on your local Mac.

### Connection refused
- Check that both Macs are on the same network
- Verify SSH works: `ssh local_server@192.168.1.194` from your local Mac
