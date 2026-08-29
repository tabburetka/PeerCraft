package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
// 26.2 removed ShareToLanScreen; the "Open to LAN" flow (incl. a built-in online/LAN scope
// toggle) moved to MultiplayerOptionsScreen. Both expose a protected init() to inject at TAIL.
//? if <26.2
import net.minecraft.client.gui.screens.ShareToLanScreen;
//? if >=26.2
/*import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;*/
import net.minecraft.network.chat.Component;
import net.peercraft.client.PeerCraftHostOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

//? if <26.2
@Mixin(ShareToLanScreen.class)
//? if >=26.2
/*@Mixin(MultiplayerOptionsScreen.class)*/
public abstract class ShareToLanScreenMixin extends Screen {

    // Value list for the "Max players" stepper — the rendezvous server independently clamps
    // to [1,32] (see RoomRegistry), but for real-world use (a room for friends) a small set
    // of GUI values is enough, not the full range.
    private static final List<Integer> MAX_PLAYERS_OPTIONS = IntStream.rangeClosed(1, 8).boxed().collect(Collectors.toList());

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addInternetCheckbox(CallbackInfo ci) {
        // Vanilla's own "Start LAN World"/"Cancel" row sits at height-28 (see
        // ShareToLanScreen.init) — everything here must stay clear of that. Stacked
        // vertically (not side-by-side like before) so the long "Allow unlicensed
        // players" label has room without needing to share a row with the max-players button.
        // Base shifted up by two more 26px rows (was height-130) to fit the "Открыть для всех"
        // checkbox and world-name field below friendsOnlyCheckbox while keeping a clear gap
        // above height-28.
        int y = this.height - 182;

        int initialMaxPlayers = MAX_PLAYERS_OPTIONS.contains(PeerCraftHostOptions.maxPlayers) ? PeerCraftHostOptions.maxPlayers : MAX_PLAYERS_OPTIONS.get(MAX_PLAYERS_OPTIONS.size() - 1);
        // CycleButton.builder(Function) lost its no-initial-value overload in 1.21.11 —
        // withInitialValue() is gone, the initial value is now a required constructor arg.
        //? if <1.21.11 {
        CycleButton.Builder<Integer> maxPlayersBuilder = CycleButton.<Integer>builder(value -> Component.literal(String.valueOf(value)))
                .withInitialValue(initialMaxPlayers);
        //?} else {
        /*CycleButton.Builder<Integer> maxPlayersBuilder = CycleButton.<Integer>builder(value -> Component.literal(String.valueOf(value)), initialMaxPlayers);*/
        //?}
        CycleButton<Integer> maxPlayersButton = maxPlayersBuilder
                .withValues(MAX_PLAYERS_OPTIONS)
                .create(this.width / 2 - 155, y + 26, 150, 20, Component.translatable("peercraft.mixin.share_to_lan.max_players"),
                        (button, value) -> PeerCraftHostOptions.maxPlayers = value);

        Checkbox allowUnlicensedCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.allow_unlicensed"), this.font)
                .pos(this.width / 2 - 155, y + 52)
                .selected(PeerCraftHostOptions.allowUnlicensedPlayers)
                .onValueChange((checkbox, value) -> PeerCraftHostOptions.allowUnlicensedPlayers = value)
                .build();

        EditBox worldNameBox = new EditBox(this.font, this.width / 2 - 155, y + 130, 150, 20, Component.translatable("peercraft.mixin.share_to_lan.world_name"));
        worldNameBox.setMaxLength(PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH);
        worldNameBox.setHint(Component.translatable("peercraft.mixin.share_to_lan.world_name_hint"));
        worldNameBox.setValue(PeerCraftHostOptions.worldName);
        worldNameBox.setResponder(value -> PeerCraftHostOptions.worldName = value);

        // Requires being logged into a PeerCraft account — with no account there's no friends
        // list to gate against, so the checkbox is shown but disabled rather than hidden (so
        // players discover the feature exists and know why it's unavailable).
        boolean loggedIn = net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession() != null;

        // Checkbox has no public API to un-check itself programmatically (its `selected`
        // field is private, toggled only by its own click handler) — so mutual exclusivity
        // between friendsOnlyCheckbox and publicRoomCheckbox below is enforced by disabling
        // the OTHER checkbox the instant one is turned on, never by force-unchecking it: a
        // disabled checkbox can't be clicked, so it simply never gets the chance to become
        // checked while the other is active. friendsOnlyHolder exists only to break the
        // circular reference (each checkbox's callback needs to see the other, but Java
        // won't let a local variable's initializer reference a not-yet-declared local).
        Checkbox[] friendsOnlyHolder = new Checkbox[1];

        // Works with or without an account (Phase 7) — unlike friendsOnlyCheckbox below,
        // always active regardless of login. Mutually exclusive with friendsOnly: a room
        // gated to friends can't also be broadcast to every anonymous player, and the server
        // enforces this too (never trusts the client alone) — see RoomRegistry.register().
        Checkbox publicRoomCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.public_room"), this.font)
                .pos(this.width / 2 - 155, y + 104)
                .selected(PeerCraftHostOptions.publicRoom)
                .onValueChange((checkbox, value) -> {
                    PeerCraftHostOptions.publicRoom = value;
                    worldNameBox.visible = value && PeerCraftHostOptions.internetPlayRequested;
                    if (friendsOnlyHolder[0] != null) {
                        friendsOnlyHolder[0].active = !value && loggedIn;
                    }
                })
                .build();

        Checkbox friendsOnlyCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.friends_only"), this.font)
                .pos(this.width / 2 - 155, y + 78)
                .selected(PeerCraftHostOptions.friendsOnly)
                .onValueChange((checkbox, value) -> {
                    PeerCraftHostOptions.friendsOnly = value;
                    publicRoomCheckbox.active = !value;
                })
                .build();
        friendsOnlyCheckbox.active = loggedIn && !PeerCraftHostOptions.publicRoom;
        publicRoomCheckbox.active = !PeerCraftHostOptions.friendsOnly;
        friendsOnlyHolder[0] = friendsOnlyCheckbox;

        // All only make sense when hosting through PeerCraft's internet path at all — hidden
        // (not just disabled) whenever that's off, and re-shown live as the checkbox toggles.
        maxPlayersButton.visible = PeerCraftHostOptions.internetPlayRequested;
        allowUnlicensedCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        friendsOnlyCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        publicRoomCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        worldNameBox.visible = PeerCraftHostOptions.internetPlayRequested && PeerCraftHostOptions.publicRoom;

        Checkbox internetCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.internet_play"), this.font)
                .pos(this.width / 2 - 155, y)
                .selected(PeerCraftHostOptions.internetPlayRequested)
                .onValueChange((checkbox, value) -> {
                    PeerCraftHostOptions.internetPlayRequested = value;
                    maxPlayersButton.visible = value;
                    allowUnlicensedCheckbox.visible = value;
                    friendsOnlyCheckbox.visible = value;
                    publicRoomCheckbox.visible = value;
                    worldNameBox.visible = value && PeerCraftHostOptions.publicRoom;
                })
                .build();

        this.addRenderableWidget(internetCheckbox);
        this.addRenderableWidget(maxPlayersButton);
        this.addRenderableWidget(allowUnlicensedCheckbox);
        this.addRenderableWidget(friendsOnlyCheckbox);
        this.addRenderableWidget(publicRoomCheckbox);
        this.addRenderableWidget(worldNameBox);
    }
}
