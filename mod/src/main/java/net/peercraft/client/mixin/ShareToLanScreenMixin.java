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
    //? if >=1.21.1 && <26.1 {
    @org.spongepowered.asm.mixin.Unique
    private final long peercraft$animationStart = System.nanoTime();

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void peercraft$renderTheme(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                                      float partialTick, CallbackInfo ci) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        net.peercraft.client.gui.SteampunkSettingsTheme.renderLan(graphics, this.font, this.width, this.height,
                this.children(), mouseX, mouseY, partialTick,
                (System.nanoTime() - this.peercraft$animationStart) / 1_000_000L);
        ci.cancel();
    }
    //?}

    //? if >=26.1 && <26.2 {
    /*@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void peercraft$extractTheme(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                      float partialTick, CallbackInfo ci) {
        net.peercraft.client.gui.SteampunkSettingsTheme.renderLan(graphics, this.font, this.width, this.height,
                this.children(), mouseX, mouseY, partialTick, 0L);
        ci.cancel();
    }*/
    //?}

    //? if >=26.2 {
    /*@Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        net.peercraft.client.gui.SteampunkSettingsTheme.renderLan(graphics, this.font, this.width, this.height,
                this.children(), mouseX, mouseY, partialTick, 0L);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void peercraft$worldBackground(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo ci) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        ci.cancel();
    }*/
    //?}

    // Value list for the "Max players" stepper — the rendezvous server independently clamps
    // to [1,32] (see RoomRegistry), but for real-world use (a room for friends) a small set
    // of GUI values is enough, not the full range.
    private static final List<Integer> MAX_PLAYERS_OPTIONS = IntStream.rangeClosed(1, 8).boxed().collect(Collectors.toList());

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addInternetCheckbox(CallbackInfo ci) {
        // ---- Widget positions -------------------------------------------------------------
        // One adaptive layout for every version: a two-column, four-row block glued to the
        // bottom of the screen, right above the vanilla button row ("Start LAN World"/"Cancel"
        // on <26.2 ShareToLanScreen, "Apply Changes"/"Cancel" footer on 26.2's
        // MultiplayerOptionsScreen). With GUI Scale "Auto" on a big monitor Minecraft picks
        // scale 3-4, this.height shrinks to ~270-360, and a fixed six-row single column no
        // longer fits the gap left by the vanilla widgets — it rides up over them and they
        // overlap. Fix:
        //   * pack the six mod widgets into four rows / two columns so the block is shorter,
        //   * keep the block bottom-anchored so it always sits low, just above the button row,
        //   * scale the row pitch + widget height to whatever vertical room is left (roomy on a
        //     tall window, tight on a short one — the same way vanilla's own widgets grow and
        //     shrink with the GUI scale), and
        //   * scale the column width down on a narrow window too (never above vanilla's 150).
        int gutter = 8;
        // Half-column width, capped at vanilla's 150; only shrinks when the window is so narrow
        // two 150px columns + margins would not fit (extreme GUI scale on a small monitor).
        int colWidth = Math.min(150, (this.width - 40 - gutter) / 2);
        int colLeft = this.width / 2 - colWidth - gutter / 2;
        int colRight = this.width / 2 + gutter / 2;

        // Vertical band left free by vanilla — the mod block drops into it, bottom-anchored:
        //  * <26.2 ShareToLanScreen (obf era + 26.1.x GuiGraphicsExtractor era — same screen
        //    structure): fixed top (title y50, info y82, game-mode/cheats row y100..120, port
        //    info y142, port field y160..180), button row at height-28. Constants are fine.
        //  * >=26.2 MultiplayerOptionsScreen: a HeaderAndFooterLayout arranges the vanilla
        //    widgets (LAN toggle, "Port Number" label + field, game-mode/commands row) and an
        //    "Apply Changes"/"Cancel" footer on its own — there is NO fixed gap and its
        //    vertical position shifts with the GUI scale, so constants land the block right on
        //    top of the vanilla widgets (as seen in testing). Measure instead: at init TAIL
        //    every vanilla widget is already positioned, so scan them — footer widgets sit in
        //    the bottom ~40px, everything else is the content cluster — and take the band as
        //    (below the lowest content widget) .. (above the highest footer widget).
        //? if <26.2 {
        int bandTop = 184;
        int bandBottom = this.height - 28 - 6;
        //?} else {
        /*int bandTop = 40;
        int bandBottom = this.height - 6;
        for (net.minecraft.client.gui.components.events.GuiEventListener child : this.children()) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget widget)) continue;
            if (widget.getY() >= this.height - 40) {
                bandBottom = Math.min(bandBottom, widget.getY() - 6);
            } else {
                bandTop = Math.max(bandTop, widget.getY() + widget.getHeight() + 6);
            }
        }*/
        //?}
        int rows = 4;
        // Pitch fills the band but stays sane: >=16 stops the stacked (fixed ~20px) checkboxes
        // from piling up hard on a short window, <=26 stops them drifting apart on a tall one.
        // Widget height (for the CycleButton / EditBox — checkboxes have no height API) tracks
        // the pitch, landing on the comfortable vanilla 20 whenever there is room.
        int rowPitch = Math.max(16, Math.min(26, (bandBottom - bandTop) / rows));
        int widgetH = Math.max(12, Math.min(20, rowPitch - 4));
        int blockHeight = rowPitch * (rows - 1) + widgetH;
        int blockTop = Math.max(bandTop, bandBottom - blockHeight);
        int buttonWidth = colWidth;
        int internetX = colLeft, internetY = blockTop;
        int allowUnlicensedX = colLeft, allowUnlicensedY = blockTop + rowPitch;
        int friendsOnlyX = colLeft, friendsOnlyY = blockTop + rowPitch * 2;
        int publicRoomX = colRight, publicRoomY = blockTop + rowPitch * 2;
        int maxPlayersX = colLeft, maxPlayersY = blockTop + rowPitch * 3;
        int worldNameX = colRight, worldNameY = blockTop + rowPitch * 3;
        int worldNameWidth = colWidth;

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
                .create(maxPlayersX, maxPlayersY, buttonWidth, widgetH, Component.translatable("peercraft.mixin.share_to_lan.max_players"),
                        (button, value) -> PeerCraftHostOptions.maxPlayers = value);

        Checkbox allowUnlicensedCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.allow_unlicensed"), this.font)
                .pos(allowUnlicensedX, allowUnlicensedY)
                .selected(PeerCraftHostOptions.allowUnlicensedPlayers)
                .onValueChange((checkbox, value) -> PeerCraftHostOptions.allowUnlicensedPlayers = value)
                .build();

        EditBox worldNameBox = new EditBox(this.font, worldNameX, worldNameY, worldNameWidth, widgetH, Component.translatable("peercraft.mixin.share_to_lan.world_name"));
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
        // the OTHER checkbox the instant one is turned on (it goes grey and stops taking
        // clicks), never by force-unchecking it. Enforced BOTH ways: picking "Open to Friends"
        // greys out "Open to Everyone" and vice versa. friendsOnlyHolder exists only to break
        // the circular reference (each checkbox's callback needs to see the other, but Java
        // won't let a local variable's initializer reference a not-yet-declared local).
        Checkbox[] friendsOnlyHolder = new Checkbox[1];

        // Works with or without an account (Phase 7) — unlike friendsOnlyCheckbox below,
        // always active regardless of login. Mutually exclusive with friendsOnly: a room
        // gated to friends can't also be broadcast to every anonymous player, and the server
        // enforces this too (never trusts the client alone) — see RoomRegistry.register().
        Checkbox publicRoomCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.public_room"), this.font)
                .pos(publicRoomX, publicRoomY)
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
                .pos(friendsOnlyX, friendsOnlyY)
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
                .pos(internetX, internetY)
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
