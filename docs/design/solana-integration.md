# Solana Integration Roadmap

**Status:** LIVING - Mixed implementation status by feature
**Version:** 1.0
**Last Updated:** 2025-10-24
**Author:** Design Review

---

## Overview

This document outlines low-hanging fruit Solana integrations that fit naturally with Bitchat's offline-first architecture. The recommendations leverage existing patterns (Nostr integration, store-and-forward, encryption) and focus on asynchronous operations that work seamlessly with the BLE mesh network.

---

## 🎯 Low-Hanging Fruit Integrations

### 1. Wallet Integration & Display

**Effort:** 1-2 days
**Priority:** HIGH (foundation for all other features)
**Implementation Status (2026-02-24):** IMPLEMENTED

**What:** Derive Solana keypair from existing Ed25519 identity and display wallet address in settings.

**Why It Fits:**
- Leverage existing `EncryptionService` and `NostrIdentity` patterns
- No internet required to show address
- Users can receive SOL even while offline
- Builds on existing cryptographic infrastructure

**Key Responsibilities:**
- Derive keypair from Ed25519 identity
- Display public address with QR code
- Sign transactions
- Export private key for backup

**Integration Points:**
- Mirror `NostrIdentity` storage pattern (EncryptedSharedPreferences)
- Add Solana settings section to UI
- Use existing key derivation infrastructure

**User Flow:**
1. App derives Solana keypair on first launch
2. Settings shows address with QR code and copy button
3. Optional: Export private key for external wallet

---

### 2. Asynchronous Payment Queue

**Effort:** 3-5 days
**Priority:** HIGH (killer feature)
**Implementation Status (2026-02-24):** IMPLEMENTED (offline queue + mesh relay + on-chain confirmation polling)

**See:** `exit-relay.md` for full design of offline transaction relay system.

**What:** Add `/tip @peer amount` command that queues Solana transfers. Broadcast transactions when internet is available.

**Why It Fits:**
- Works offline: Queue payments locally
- Use `StoreForwardManager` pattern (12h message caching)
- Natural fit for mesh network's asynchronous nature
- Exit relay enables offline users to submit via nearby online peers

**Payment States:**
- QUEUED → Waiting for internet
- BROADCASTING → Sending to RPC
- CONFIRMED → On-chain confirmation
- FAILED → RPC error or timeout

**Integration Points:**
- Mirror `StoreForwardManager` queue persistence
- Add message type `0x23` for payment receipts
- Reuse `NostrTransport` connection logic for Solana RPC
- Display payment status in chat UI

**User Flow:**
```
/tip @alice 0.5 coffee thanks!
→ [System] Payment queued: 0.5 SOL to alice (Pending internet)
→ [System] Broadcasting payment...
→ [System] ✓ Payment confirmed: 0.5 SOL to alice
```

---

### 3. Token-Gated Channels

**Effort:** 3-4 days
**Priority:** MEDIUM
**Implementation Status (2026-02-24):** IMPLEMENTED (SPL + NFT specific + NFT collection, with cache + RPC revalidation)

**What:** Channels that require holding specific SPL tokens or NFTs to join.

**Why It Fits:**
- Check token balance when joining channel
- Cache eligibility for offline access (TTL: 24h)
- Revalidate periodically when internet available
- Mesh messages still work offline after initial validation
- Enables premium/exclusive communities

**Token Gate Types:**
- SPL_TOKEN - Fungible tokens (e.g., 100 $CHAT minimum)
- NFT_COLLECTION - Any NFT from collection
- NFT_SPECIFIC - Specific NFT mint

**Integration Points:**
- Add token gate validation on `/join` command
- Query Solana RPC for token balances (getTokenAccountsByOwner)
- Cache validation results in DataManager
- Reject messages from non-holders in SecurityManager

**User Flow:**
```
/join #premium-channel
→ [System] Required: 100 $CHAT tokens
→ [System] Your balance: 250 $CHAT ✓
→ [System] Joined #premium-channel (Valid until 2025-10-24)

# Offline later - cache still valid
/join #premium-channel
→ [System] Joined #premium-channel (using cached validation)
```

**Channel Creation:**
```
/create #vip --token-gate <mint> --min-balance 1000
→ [System] Created token-gated channel #vip
→ Required: 1000 USDC
```

---

### 4. Proof-of-Ownership Announcements

**Effort:** 2-3 days
**Priority:** MEDIUM
**Implementation Status (2026-02-24):** IMPLEMENTED (wallet-link proof + signed ownership claims + verification + proof caps)

**What:** Include Solana wallet address in BLE `ANNOUNCE` messages. Peers can verify token/NFT ownership offline (cached) or online.

**Why It Fits:**
- Piggyback on existing ANNOUNCE protocol (message type `0x01`)
- Add TLV field for Solana address
- Works completely offline for display
- Optional online verification for badges/reputation
- Natural extension of peer identity

**New ANNOUNCE TLV Fields:**
- 0x05: Solana Address (Base58 UTF-8 string, variable length)
- 0x06: Solana Wallet-Link Proof Signature (variable)
- 0x07: Token/NFT Ownership Proof (variable, repeated)
- 0x08: NFT Mint (Base58 UTF-8 string, variable length, optional - profile avatar)

**Integration Points:**
- Extend ANNOUNCE TLV encoding/decoding in `IdentityAnnouncement`
- Parse Solana fields in MessageHandler
- Store Solana address per peer in PeerManager
- Display badges/NFT avatars in peer list UI

**UI Enhancements:**
- Show Solana address in peer details
- Badge for verified token holders (🏆)
- NFT profile pictures (cache metadata from Metaplex)
- Reputation indicators based on holdings

**User Flow:**
```
[Peer: alice] joined (0x1a2b3c...)
Solana: 7xKXtg...9fZ3
Holdings: 1000 $CHAT, 5 NFTs

# Tap peer to view details
Alice (0x1a2b3c...)
└─ Solana: 7xKXtg2TwvkuqCdEcxEzNj9fZ3
└─ Badges: 🏆 $CHAT Holder, 🎨 NFT Collector
└─ Last seen: 2 minutes ago
```

---

### 5. Message Notarization

**Effort:** 2-3 days
**Priority:** LOW (advanced feature)
**Implementation Status (2026-02-24):** IMPLEMENTED (queue + batching + recovery + slot/blockTime proof metadata)

**What:** Hash critical messages and post to Solana for immutable timestamping. Useful for proof of authorship and compliance.

**Why It Fits:**
- Messages composed offline
- Hash computed locally (no internet)
- Notarize batch of hashes when internet available
- Uses existing `StoreForwardManager` queue pattern
- Leverages Solana's high throughput for cheap timestamping

**Notarization Proof Contains:**
- Message hash (SHA-256)
- Transaction signature
- Block time
- Slot number

**Integration Points:**
- Add "Notarize" action to message long-press menu
- Post batched hashes to on-chain program
- Queue pattern mirrors StoreForwardManager
- Store proofs in DataManager

**User Flow:**
```
# Long-press message
[Actions] Copy | Forward | Notarize

# Select Notarize
[System] Message queued for blockchain notarization
Hash: 0x9f3a2b...

# When internet available
[System] Notarized batch of 5 messages
Transaction: https://solscan.io/tx/abc123...

# Verify later
[Message] "Agreement finalized" ✓ Notarized
└─ Proof: Slot 123456789, 2025-10-23 14:32:11 UTC
```

**Use Cases:**
- Proof of authorship for important announcements
- Legal/compliance documentation
- Timestamped consensus in group decisions
- Immutable audit trail for business communications

---

## Architecture Integration

### Package Structure

Create: `app/src/main/java/com/bitchat/android/solana/`

**Core Components:**
- `SolanaWalletManager` - Keypair derivation, address display
- `SolanaPaymentQueue` - Offline payment queue
- `SolanaRpcClient` - RPC calls (reuse OkHttp)
- `SolanaTokenManager` - SPL token queries
- `SolanaTokenGate` - Token verification
- `SolanaMessageNotary` - Message hashing & notarization

### Integration Strategy

**Mirror existing patterns:**
- `nostr/NostrIdentity` → `solana/SolanaWalletManager`
- `nostr/NostrClient` → `solana/SolanaRpcClient`
- `nostr/NostrTransport` → `solana/SolanaPaymentQueue`

**Use existing infrastructure:**
- `OkHttpProvider` for RPC calls (HTTP POST to Solana endpoints)
- `EncryptedSharedPreferences` for private keys and cached balances
- `StoreForwardManager` pattern for payment queue
- `TorManager` for optional privacy-preserving RPC calls

---

## Implementation Roadmap

### Phase 1: Foundation (Week 1)
**Features #1 + #4 together:**

- Derive Solana wallet from existing identity
- Display address in settings
- Include in ANNOUNCE messages
- Show peer Solana addresses in UI

**Deliverables:**
- Users get Solana wallet automatically
- Can receive payments externally
- Build reputation around persistent address

### Phase 2: Payments (Week 2)
**Feature #2 (payment queue) - See exit-relay.md:**

- Implement `/tip` command
- Queue payments locally
- Integrate Solana RPC client
- Broadcast when internet available
- Show transaction status in UI

**Deliverables:**
- Peer-to-peer SOL transfers
- Works offline (queued)
- Exit relay support for offline users

### Phase 3: Advanced Features (Week 3-4)
**Features #3 (token gates) and #5 (notarization):**

- Token-gated channel creation
- SPL token balance verification
- Message notarization
- NFT profile pictures (optional)

---

## Security Considerations

### Key Management
- Derive keypair from existing Ed25519 identity
- Store in EncryptedSharedPreferences (mirrors NostrIdentity pattern)
- Never expose private keys in logs or UI
- Backup warning: Prompt users to backup seed phrase
- Recovery flow: Allow import of external Solana wallet

### Transaction Security
- Show transaction fees before signing
- Confirm recipient address in UI dialog
- Rate-limit payments to prevent spam (max 10/hour)
- Validate RPC responses cryptographically
- Timeout failed transactions (60s default)

### Privacy
- Optional Tor routing for RPC calls (via TorManager)
- Cached balances expire (24h TTL) to prevent stale data
- Solana transactions are public (no additional privacy loss)

### Network Security
- Verify RPC endpoint SSL certificates
- Fallback to multiple RPC providers (Helius, Quicknode, Alchemy)
- Detect and warn on RPC errors or forks
- Validate transaction signatures before displaying as confirmed

---

---

---

## 🎨 UI/UX Mockups

### Settings Screen Addition
```
┌─────────────────────────────────┐
│ Solana Wallet                   │
├─────────────────────────────────┤
│ Address:                        │
│ 7xKXtg2TwvkuqCdEcxEzNj9fZ3     │
│ [Copy] [QR Code] [Export]       │
│                                 │
│ Balance: 1.23 SOL               │
│ (Refresh needed)                │
│                                 │
│ ☐ Enable automatic payments    │
│ ☐ Route via Tor                 │
└─────────────────────────────────┘
```

### Chat Command Examples
```
/tip @alice 0.5 thanks!
/create #vip --token-gate <mint> --min-balance 100
/notarize (long-press message)
/wallet (show balance)
/export-key (backup private key)
```

### Peer List Enhancement
```
┌─────────────────────────────────┐
│ alice 🏆                        │
│ └─ 0x1a2b... · RSSI: -45 dBm    │
│ └─ 7xKXtg...9fZ3               │
│ └─ 1000 $CHAT, 5 NFTs           │
└─────────────────────────────────┘
```

---


---

## Related Documents

### Existing Patterns to Mirror
- `nostr/NostrIdentity` - Identity management
- `nostr/NostrClient` - RPC client structure
- `nostr/NostrTransport` - Async message relay
- `mesh/StoreForwardManager` - Queue persistence
- `crypto/EncryptionService` - Key derivation

### Related Bitchat Design Docs
- `exit-relay.md` - Exit relay design (offline transaction relay)
- `sync.md` - Gossip sync protocol
- `file_transfer.md` - Large payload handling
- `source-routing.md` - Multi-hop routing

### External References
- [Solana JSON RPC API](https://solana.com/docs/rpc)
- [SPL Token Program](https://spl.solana.com/token)
- [Metaplex Metadata](https://docs.metaplex.com/)
- [Solana Cookbook](https://solanacookbook.com/)

---

**Document Status:** LIVING - Implementation Tracking
**Dependencies:** Exit relay design (exit-relay.md)
**Estimated Time:** Phased approach (3-4 weeks total)
