package com.bitchat.android.ui.theme

/**
 * Pixel art icon grids for BitChat's retro terminal aesthetic.
 * Each icon is a 2D array where 1 = filled pixel, 0 = empty.
 * Rendered by PixelArtPainter via rememberPixelPainter().
 *
 * Design rules:
 *   7x7 grids — all icons shown at <20dp
 *   8x8 grids — icons shown at 20dp+
 *   2px minimum stroke width for visibility on high-DPI screens
 */
object PixelIcons {

    // ─── Navigation ────────────────────────────────────────

    /** ← Back arrow (7x7) - chunky left-pointing arrow */
    val ArrowBack = arrayOf(
        intArrayOf(0,0,1,0,0,0,0),
        intArrayOf(0,1,1,0,0,0,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,1,1,0,0,0,0),
        intArrayOf(0,0,1,0,0,0,0),
    )

    /** ↑ Send / arrow up (7x7) - bold upward arrow */
    val ArrowUp = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,0,1,0,1,1),
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,0,1,0,0,0),
    )

    /** ↓ Arrow down / scroll to bottom (7x7) */
    val ArrowDown = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(1,1,0,1,0,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,0,1,0,0,0),
    )

    /** → Right arrow / chevron (7x7) */
    val ArrowRight = arrayOf(
        intArrayOf(0,1,1,0,0,0,0),
        intArrayOf(0,0,1,1,0,0,0),
        intArrayOf(0,0,0,1,1,0,0),
        intArrayOf(0,0,0,0,1,1,0),
        intArrayOf(0,0,0,1,1,0,0),
        intArrayOf(0,0,1,1,0,0,0),
        intArrayOf(0,1,1,0,0,0,0),
    )

    // ─── Actions ───────────────────────────────────────────

    /** ✕ Close / cancel (7x7) - bold X */
    val Close = arrayOf(
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,1,0,1,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,1,0,1,1,1),
        intArrayOf(1,1,0,0,0,1,1),
    )

    /** ✓ Check / confirm (7x7) - thick checkmark */
    val Check = arrayOf(
        intArrayOf(0,0,0,0,0,1,1),
        intArrayOf(0,0,0,0,1,1,1),
        intArrayOf(0,0,0,1,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(1,1,1,1,0,0,0),
        intArrayOf(1,1,1,0,0,0,0),
        intArrayOf(0,1,0,0,0,0,0),
    )

    /** ✓ Check circle / success (8x8) - filled circle with check cutout */
    val CheckCircle = arrayOf(
        intArrayOf(0,1,1,1,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,0,1),
        intArrayOf(1,1,1,1,1,0,1,1),
        intArrayOf(1,0,1,1,0,1,1,1),
        intArrayOf(1,1,0,0,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(0,1,1,1,1,1,1,0),
    )

    /** + Plus / add (7x7) - thick plus */
    val Add = arrayOf(
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
    )

    /** - Minus / remove (7x7) */
    val Remove = arrayOf(
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(0,0,0,0,0,0,0),
    )

    /** Copy / clipboard (8x8) - two overlapping filled rectangles */
    val Copy = arrayOf(
        intArrayOf(0,0,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,0,0),
        intArrayOf(0,0,0,0,0,0,0,0),
    )

    /** Download / save (7x7) */
    val Download = arrayOf(
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,1),
    )

    // ─── Security / Encryption ─────────────────────────────

    /** Locked padlock (7x7) - solid body, thick shackle */
    val Lock = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,0,1,1,0),
        intArrayOf(0,1,1,0,1,1,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    /** Unlocked padlock (7x7) */
    val Unlock = arrayOf(
        intArrayOf(0,0,0,1,1,1,0),
        intArrayOf(0,0,0,1,0,1,0),
        intArrayOf(0,0,0,0,0,1,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    /** Shield / security (7x7) - solid filled shield */
    val Shield = arrayOf(
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,0,1,0,0,0),
    )

    // ─── Connectivity ──────────────────────────────────────

    /** Bluetooth rune (7x7) - thick B-rune */
    val Bluetooth = arrayOf(
        intArrayOf(0,0,1,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(1,0,1,0,1,1,0),
        intArrayOf(0,1,1,1,1,0,0),
        intArrayOf(1,0,1,0,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,0,0,0),
    )

    /** Sync / rotating arrows (7x7) */
    val Sync = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,0,0,0,0,0,0),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(0,0,0,0,0,0,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Globe / public / internet (7x7) - filled globe with cross lines */
    val Globe = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,0,0,1,0,0,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,0,0,1,0,0,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,0,0,1,0,0,1),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Antenna / broadcast (7x7) - bold tower with signal */
    val Antenna = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,1,1,0,1,1,0),
        intArrayOf(1,1,0,0,0,1,1),
    )

    /** Ethernet / network (7x7) */
    val Network = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,0,0,0,1,0),
        intArrayOf(0,1,0,0,0,1,0),
        intArrayOf(0,1,0,0,0,1,0),
        intArrayOf(0,1,0,0,0,1,0),
        intArrayOf(0,1,1,1,1,1,0),
    )

    // ─── People / Users ────────────────────────────────────

    /** Person / user (7x7) - solid head and shoulders */
    val Person = arrayOf(
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    /** Group / multiple people (8x7) - two side-by-side figures */
    val Group = arrayOf(
        intArrayOf(0,1,1,0,0,1,1,0),
        intArrayOf(0,1,1,0,0,1,1,0),
        intArrayOf(0,0,0,0,0,0,0,0),
        intArrayOf(1,1,1,0,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(0,1,1,1,1,1,1,0),
    )

    // ─── Communication ─────────────────────────────────────

    /** Envelope / email / DM (8x7) - filled envelope with V flap */
    val Email = arrayOf(
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,0,1,0,0,1,0,1),
        intArrayOf(1,0,0,1,1,0,0,1),
        intArrayOf(1,0,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,0,1),
        intArrayOf(1,1,1,1,1,1,1,1),
    )

    // ─── Location ──────────────────────────────────────────

    /** Map pin / location (7x7) - solid pin */
    val LocationPin = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,0,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,0,1,0,0,0),
    )

    /** Pin drop / teleport (7x7) */
    val PinDrop = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,0,1,1,1,0,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Map / world grid (7x7) */
    val Map = arrayOf(
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,0,1,0,0,1,1),
        intArrayOf(1,0,1,0,0,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,0,1,0,0,1,1),
        intArrayOf(1,0,1,0,0,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    // ─── Favorites / Bookmarks ─────────────────────────────

    /** Star filled (7x7) - solid chunky star */
    val StarFilled = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,0,1,1,0),
        intArrayOf(1,1,0,0,0,1,1),
    )

    /** Star outline (7x7) - thick outline star */
    val StarOutline = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(1,1,1,0,1,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(0,1,1,0,1,1,0),
        intArrayOf(0,1,0,0,0,1,0),
        intArrayOf(1,1,0,0,0,1,1),
    )

    /** Bookmark filled (7x7) - solid bookmark flag */
    val BookmarkFilled = arrayOf(
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,0,0,0,0,0,1),
    )

    /** Bookmark outline (7x7) - thick outline */
    val BookmarkOutline = arrayOf(
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,0,0,0,0,0,1),
    )

    // ─── Media / Files ─────────────────────────────────────

    /** Camera / photo (8x7) - bold camera body with lens */
    val Camera = arrayOf(
        intArrayOf(0,0,1,1,1,0,0,0),
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(1,1,0,1,1,0,1,1),
        intArrayOf(1,0,1,1,1,1,0,1),
        intArrayOf(1,0,1,1,1,1,0,1),
        intArrayOf(1,1,0,1,1,0,1,1),
        intArrayOf(1,1,1,1,1,1,1,1),
    )

    /** File / document (7x7) - solid page with corner fold */
    val File = arrayOf(
        intArrayOf(1,1,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    /** Attachment / paperclip (7x7) - thick clip shape */
    val Attachment = arrayOf(
        intArrayOf(0,0,0,1,1,1,0),
        intArrayOf(0,0,1,1,0,1,1),
        intArrayOf(0,1,1,0,0,1,1),
        intArrayOf(0,1,1,0,0,1,1),
        intArrayOf(0,1,1,0,1,1,0),
        intArrayOf(0,1,1,1,1,0,0),
        intArrayOf(0,0,1,1,0,0,0),
    )

    /** Link / chain (7x7) - two thick interlocking links */
    val Link = arrayOf(
        intArrayOf(0,0,0,1,1,1,0),
        intArrayOf(0,0,1,0,0,1,1),
        intArrayOf(0,0,1,0,0,1,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,0,0,1,0,0),
        intArrayOf(1,1,0,0,1,0,0),
        intArrayOf(0,1,1,1,0,0,0),
    )

    /** Play triangle (7x7) - solid filled triangle */
    val Play = arrayOf(
        intArrayOf(1,1,0,0,0,0,0),
        intArrayOf(1,1,1,1,0,0,0),
        intArrayOf(1,1,1,1,1,0,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,0,0),
        intArrayOf(1,1,1,1,0,0,0),
        intArrayOf(1,1,0,0,0,0,0),
    )

    /** Pause bars (7x7) - two thick bars */
    val Pause = arrayOf(
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,1,1),
    )

    /** Microphone (7x7) - solid mic */
    val Mic = arrayOf(
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,0,0,0,1,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,0,1,0,0,0),
    )

    // ─── Wallet / Finance ──────────────────────────────────

    /** Wallet (8x7) - solid wallet with clasp */
    val Wallet = arrayOf(
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(1,0,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,1,1,1),
        intArrayOf(1,0,0,0,0,1,0,1),
        intArrayOf(1,0,0,0,0,1,1,1),
        intArrayOf(1,1,1,1,1,1,1,1),
    )

    // ─── Status / Info ─────────────────────────────────────

    /** Warning triangle (7x7) - solid triangle with dot */
    val Warning = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,0,1,0,1,0,0),
        intArrayOf(0,1,1,0,1,1,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,1,0,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    /** Error / circle-X (7x7) - filled circle with X cutout */
    val Error = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,0,1,0,1,1),
        intArrayOf(1,0,0,1,0,0,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,0,0,1,0,0,1),
        intArrayOf(1,1,0,1,0,1,1),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Notification bell (7x7) - solid bell */
    val Bell = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(0,0,1,1,1,0,0),
    )

    // ─── Interface ─────────────────────────────────────────

    /** Hamburger menu (7x7) - three thick bars */
    val Menu = arrayOf(
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(1,1,1,1,1,1,1),
    )

    /** Settings / gear (8x8) - bold gear */
    val Settings = arrayOf(
        intArrayOf(0,0,1,1,1,1,0,0),
        intArrayOf(0,1,1,1,1,1,1,0),
        intArrayOf(1,1,1,0,0,1,1,1),
        intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,1,1,0,0,1,1,1),
        intArrayOf(0,1,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,1,0,0),
    )

    /** Bug / debug (7x7) - chunky bug */
    val Bug = arrayOf(
        intArrayOf(1,0,1,1,1,0,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,0,1,0,1,0,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,0,1,0,1,0,1),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,0,1,1,1,0,0),
    )

    /** Power button (7x7) */
    val Power = arrayOf(
        intArrayOf(0,0,0,1,0,0,0),
        intArrayOf(0,1,0,1,0,1,0),
        intArrayOf(1,1,0,1,0,1,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Battery (7x7) - filled battery */
    val Battery = arrayOf(
        intArrayOf(0,0,1,1,1,0,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Devices / screens (8x7) - monitor shape */
    val Devices = arrayOf(
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(1,0,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,0,1),
        intArrayOf(1,1,1,1,1,1,1,1),
        intArrayOf(0,0,0,1,1,0,0,0),
        intArrayOf(0,0,1,1,1,1,0,0),
    )

    /** Route / path (7x7) - S-curve path */
    val Route = arrayOf(
        intArrayOf(0,0,0,0,1,1,1),
        intArrayOf(0,0,0,0,0,1,1),
        intArrayOf(0,0,0,1,1,1,0),
        intArrayOf(0,0,0,0,0,0,0),
        intArrayOf(0,1,1,1,0,0,0),
        intArrayOf(1,1,0,0,0,0,0),
        intArrayOf(1,1,1,0,0,0,0),
    )

    /** Circle outline (7x7) - thick circle */
    val Circle = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Eye / visibility (8x7) - thick eye shape */
    val Visibility = arrayOf(
        intArrayOf(0,0,0,0,0,0,0,0),
        intArrayOf(0,0,1,1,1,1,0,0),
        intArrayOf(0,1,1,0,0,1,1,0),
        intArrayOf(1,1,0,1,1,0,1,1),
        intArrayOf(0,1,1,0,0,1,1,0),
        intArrayOf(0,0,1,1,1,1,0,0),
        intArrayOf(0,0,0,0,0,0,0,0),
    )

    /** Explore / compass (7x7) */
    val Explore = arrayOf(
        intArrayOf(0,1,1,1,1,1,0),
        intArrayOf(1,0,0,0,1,0,1),
        intArrayOf(1,0,0,1,1,0,1),
        intArrayOf(1,0,1,1,0,0,1),
        intArrayOf(1,0,1,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(0,1,1,1,1,1,0),
    )

    /** Checkbox checked (7x7) - filled box with check cutout */
    val CheckboxOn = arrayOf(
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,0,1),
        intArrayOf(1,0,1,1,0,1,1),
        intArrayOf(1,1,0,0,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    /** Checkbox unchecked (7x7) - thick border box */
    val CheckboxOff = arrayOf(
        intArrayOf(1,1,1,1,1,1,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,0,0,0,0,0,1),
        intArrayOf(1,1,0,0,0,1,1),
        intArrayOf(1,1,1,1,1,1,1),
    )

    // QR code icon — stylized QR pattern
    val QrCode = arrayOf(
        intArrayOf(1,1,1,0,1,0,1,1),
        intArrayOf(1,0,1,0,0,0,1,0),
        intArrayOf(1,1,1,0,1,0,1,1),
        intArrayOf(0,0,0,0,0,1,0,0),
        intArrayOf(1,0,1,0,1,0,1,0),
        intArrayOf(0,0,0,1,0,0,0,1),
        intArrayOf(1,1,1,0,1,0,1,1),
        intArrayOf(1,0,1,0,0,1,1,0),
    )
}
