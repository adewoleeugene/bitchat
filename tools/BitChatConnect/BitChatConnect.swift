import Cocoa
import SwiftUI

// MARK: - App Entry Point

@main
struct BitChatConnectApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        Settings { EmptyView() }
    }
}

// MARK: - App Delegate

class AppDelegate: NSObject, NSApplicationDelegate {
    var statusItem: NSStatusItem!
    var popover: NSPopover!
    let connectionManager = ConnectionManager()

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Hide dock icon
        NSApp.setActivationPolicy(.accessory)

        // Create status bar item
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "antenna.radiowaves.left.and.right",
                                   accessibilityDescription: "BitChat Connect")
            button.action = #selector(togglePopover)
            button.target = self
        }

        // Create popover
        popover = NSPopover()
        popover.contentSize = NSSize(width: 300, height: 340)
        popover.behavior = .transient
        popover.contentViewController = NSHostingController(
            rootView: MenuBarView(manager: connectionManager)
        )

        // Update icon based on connection state
        connectionManager.onStatusChange = { [weak self] connected in
            DispatchQueue.main.async {
                self?.updateIcon(connected: connected)
            }
        }
    }

    func updateIcon(connected: Bool) {
        if let button = statusItem.button {
            let symbolName = connected
                ? "antenna.radiowaves.left.and.right"
                : "antenna.radiowaves.left.and.right.slash"
            button.image = NSImage(systemSymbolName: symbolName,
                                   accessibilityDescription: "BitChat Connect")
            // Tint green when connected
            button.contentTintColor = connected ? .systemGreen : nil
        }
    }

    @objc func togglePopover() {
        if let button = statusItem.button {
            if popover.isShown {
                popover.performClose(nil)
            } else {
                popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
                NSApp.activate(ignoringOtherApps: true)
            }
        }
    }
}

// MARK: - Connection Manager

class ConnectionManager: ObservableObject {
    @Published var status: ConnectionStatus = .disconnected
    @Published var deviceName: String = ""
    @Published var logs: [LogEntry] = []

    var onStatusChange: ((Bool) -> Void)?

    private var sshProcess: Process?
    private var monitorTimer: Timer?

    private let adbPath = "\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb"
    private let remoteHost = "local_server@server.local"

    enum ConnectionStatus: String {
        case disconnected = "Disconnected"
        case connecting = "Connecting..."
        case connected = "Connected"
        case error = "Error"
    }

    struct LogEntry: Identifiable {
        let id = UUID()
        let time: Date
        let message: String
    }

    func connect() {
        guard status != .connecting && status != .connected else { return }
        setStatus(.connecting)
        log("Starting connection...")

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.performConnect()
        }
    }

    func disconnect() {
        log("Disconnecting...")
        sshProcess?.terminate()
        sshProcess = nil
        monitorTimer?.invalidate()
        monitorTimer = nil
        setStatus(.disconnected)
        deviceName = ""
        log("Disconnected.")
    }

    private func performConnect() {
        // Step 1: Kill remote ADB server so tunnel can take port 5037
        log("Killing remote ADB server...")
        runCommand(adbPath, arguments: ["kill-server"])
        Thread.sleep(forTimeInterval: 1)

        // Step 2: Check if local ADB + emulator are reachable
        // (The local Mac should have ADB running with an emulator)
        log("Starting SSH tunnel...")

        // Step 3: Start SSH tunnel
        let ssh = Process()
        ssh.executableURL = URL(fileURLWithPath: "/usr/bin/ssh")
        ssh.arguments = [
            "-N",                              // No remote command
            "-o", "ExitOnForwardFailure=yes",  // Fail if tunnel can't bind
            "-o", "ServerAliveInterval=30",    // Keep alive
            "-o", "ServerAliveCountMax=3",
            "-o", "ConnectTimeout=10",
            "-R", "5037:localhost:5037",       // Reverse tunnel ADB
            remoteHost
        ]

        let pipe = Pipe()
        ssh.standardError = pipe

        ssh.terminationHandler = { [weak self] process in
            DispatchQueue.main.async {
                if self?.status == .connected || self?.status == .connecting {
                    self?.log("SSH tunnel closed (exit \(process.terminationStatus)).")
                    self?.setStatus(.disconnected)
                    self?.deviceName = ""
                    self?.monitorTimer?.invalidate()
                }
            }
        }

        do {
            try ssh.run()
            sshProcess = ssh
        } catch {
            DispatchQueue.main.async { [weak self] in
                self?.log("Failed to start SSH: \(error.localizedDescription)")
                self?.setStatus(.error)
            }
            return
        }

        // Give tunnel a moment to establish
        Thread.sleep(forTimeInterval: 2)

        // Step 4: Check for devices
        DispatchQueue.main.async { [weak self] in
            self?.checkDevices()
            // Start monitoring
            self?.monitorTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { _ in
                self?.checkDevices()
            }
        }
    }

    func checkDevices() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }
            let output = self.runCommand(self.adbPath, arguments: ["devices"])
            let lines = output.components(separatedBy: "\n")
                .filter { $0.contains("\tdevice") }

            DispatchQueue.main.async {
                if let device = lines.first {
                    let name = device.components(separatedBy: "\t").first ?? "Unknown"
                    self.deviceName = name
                    if self.status != .connected {
                        self.setStatus(.connected)
                        self.log("Device found: \(name)")
                        self.showNotification(title: "BitChat Connected",
                                              body: "Emulator \(name) is ready")
                    }
                } else {
                    if self.status == .connected {
                        self.deviceName = ""
                        self.log("Device lost!")
                        self.setStatus(.connecting)
                    } else if self.status == .connecting {
                        self.log("Waiting for emulator... (start it on your local Mac)")
                    }
                }
            }
        }
    }

    private func setStatus(_ newStatus: ConnectionStatus) {
        DispatchQueue.main.async { [weak self] in
            self?.status = newStatus
            self?.onStatusChange?(newStatus == .connected)
        }
    }

    private func log(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            let entry = LogEntry(time: Date(), message: message)
            self?.logs.append(entry)
            // Keep last 50 entries
            if (self?.logs.count ?? 0) > 50 {
                self?.logs.removeFirst()
            }
        }
    }

    private func showNotification(title: String, body: String) {
        let notification = NSUserNotification()
        notification.title = title
        notification.informativeText = body
        notification.soundName = NSUserNotificationDefaultSoundName
        NSUserNotificationCenter.default.deliver(notification)
    }

    @discardableResult
    private func runCommand(_ path: String, arguments: [String] = []) -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments

        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        do {
            try process.run()
            process.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            return String(data: data, encoding: .utf8) ?? ""
        } catch {
            return "Error: \(error.localizedDescription)"
        }
    }
}

// MARK: - Menu Bar View

struct MenuBarView: View {
    @ObservedObject var manager: ConnectionManager

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack {
                Image(systemName: "bitcoinsign.circle.fill")
                    .font(.title2)
                    .foregroundColor(.orange)
                Text("BitChat Connect")
                    .font(.headline)
                Spacer()
            }
            .padding(.bottom, 4)

            Divider()

            // Status
            HStack(spacing: 8) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 10, height: 10)
                Text(manager.status.rawValue)
                    .font(.system(.body, design: .monospaced))
                Spacer()
            }

            // Device info
            if !manager.deviceName.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "iphone")
                        .foregroundColor(.secondary)
                    Text(manager.deviceName)
                        .font(.system(.callout, design: .monospaced))
                        .foregroundColor(.secondary)
                    Spacer()
                }
            }

            // Connect / Disconnect button
            Button(action: {
                if manager.status == .connected || manager.status == .connecting {
                    manager.disconnect()
                } else {
                    manager.connect()
                }
            }) {
                HStack {
                    Spacer()
                    Image(systemName: isConnected ? "stop.fill" : "play.fill")
                    Text(isConnected ? "Disconnect" : "Connect")
                        .fontWeight(.medium)
                    Spacer()
                }
                .padding(.vertical, 8)
                .background(isConnected ? Color.red.opacity(0.8) : Color.green.opacity(0.8))
                .foregroundColor(.white)
                .cornerRadius(8)
            }
            .buttonStyle(.plain)

            Divider()

            // Log
            Text("Log")
                .font(.caption)
                .foregroundColor(.secondary)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(manager.logs) { entry in
                            Text("\(timeString(entry.time)) \(entry.message)")
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundColor(.secondary)
                                .id(entry.id)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 100)
                .onChange(of: manager.logs.count) { _ in
                    if let last = manager.logs.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }

            Divider()

            // Quit
            Button("Quit") {
                manager.disconnect()
                NSApp.terminate(nil)
            }
            .foregroundColor(.secondary)
            .font(.caption)
        }
        .padding(16)
    }

    var isConnected: Bool {
        manager.status == .connected || manager.status == .connecting
    }

    var statusColor: Color {
        switch manager.status {
        case .connected: return .green
        case .connecting: return .yellow
        case .disconnected: return .gray
        case .error: return .red
        }
    }

    func timeString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: date)
    }
}
