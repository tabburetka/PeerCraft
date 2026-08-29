package net.peercraft.client;

import net.peercraft.config.PeerCraftConfig;

// The "publish the world over the internet or just locally" decision is now made by the
// host fresh every time on the "Open to LAN" screen (a checkbox), rather than once at
// launch via -Dpeercraft.internetPlay. The checkbox's initial state is taken from the
// config — this keeps -Dpeercraft.internetPlay=true as a convenient default for a machine
// that always hosts over the internet, without forcing that on everyone else.
public final class PeerCraftHostOptions {
    public static volatile boolean internetPlayRequested = PeerCraftConfig.internetPlay();
    public static volatile int maxPlayers = PeerCraftConfig.maxPlayers();
    // true (default) preserves the mod's original behavior — both licensed and unlicensed
    // ("pirate") clients can join. false leaves the IntegratedServer's Mojang authentication
    // enabled (it's already on by default at world startup, see IntegratedServer.initServer —
    // OpenToLanMixin previously always turned it off unconditionally) so only players who can
    // actually pass the real Mojang session handshake get in.
    public static volatile boolean allowUnlicensedPlayers = true;
    // false (default): access to the room is via room code alone, as before. true: JOIN is
    // additionally gated to accounts on the host's PeerCraft friends list, even if the
    // joiner knows a valid code — see RoomRegistry.join() on the rendezvous server. Only
    // takes effect when the host is logged into a PeerCraft account (see
    // ShareToLanScreenMixin, which disables the checkbox otherwise).
    public static volatile boolean friendsOnly = false;
    // false (default): unchanged behavior. true: the room is listed in the public game
    // browser (Multiplayer -> Games tab) for ANY player with the mod — no account, no
    // friendship, no code needed — see RoomRegistry.listPublicRooms() on the rendezvous
    // server. Unlike friendsOnly, this works without being logged into a PeerCraft account.
    // Mutually exclusive with friendsOnly — see ShareToLanScreenMixin.
    public static volatile boolean publicRoom = false;
    // Free-text label shown next to the host's name in the public game browser (Phase 7).
    // Only meaningful while publicRoom is true. Left blank, OpenToLanMixin falls back to the
    // actual Minecraft save name instead of listing the room with no name at all.
    public static volatile String worldName = "";
    // Bounds worldName (whether typed by the host or taken from the save name as a fallback)
    // so a single row can't blow out the public browser's UDP reply payload — see
    // RendezvousProtocol's short-string trailer format (a 1-byte length prefix, so this must
    // stay well under 255 even at 4 bytes/char worst case for non-ASCII names).
    public static final int MAX_WORLD_NAME_LENGTH = 32;

    private PeerCraftHostOptions() {
    }
}
