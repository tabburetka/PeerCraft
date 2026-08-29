package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.PeerCraftHostOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../ShareToLanScreenMixin.java}. Same widgets
 * (internet-play checkbox, max-players stepper, allow-unlicensed / friends-only / public-room
 * checkboxes, world-name field) added at {@code ShareToLanScreen.init()} TAIL, but rebuilt on
 * the 1.16.5 API:
 * <ul>
 *   <li>{@link Checkbox} has no {@code onValueChange} builder — {@link CallbackCheckbox}
 *       subclasses it and fires a {@link Consumer} from {@code onPress()}.</li>
 *   <li>{@code CycleButton} doesn't exist yet — the max-players stepper is a plain
 *       {@link Button} that cycles {@link #MAX_PLAYERS_OPTIONS} and rewrites its own label.</li>
 *   <li>{@code EditBox.setHint} → {@code setSuggestion(String)}; {@code addRenderableWidget}
 *       → {@code addButton}; {@code Component.translatable/literal} →
 *       {@code TranslatableComponent}/{@code TextComponent}.</li>
 * </ul>
 * The friends-only / public-room mutual exclusion is still enforced by disabling the other
 * checkbox (never force-unchecking), exactly as documented in the original.
 */
@Mixin(ShareToLanScreen.class)
public abstract class ShareToLanScreenMixin extends Screen {

    private static final List<Integer> MAX_PLAYERS_OPTIONS = IntStream.rangeClosed(1, 8).boxed().collect(Collectors.toList());

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addInternetCheckbox(CallbackInfo ci) {
        int left = this.width / 2 - 155;
        int y = this.height - 182;

        int initialMaxPlayers = MAX_PLAYERS_OPTIONS.contains(PeerCraftHostOptions.maxPlayers)
                ? PeerCraftHostOptions.maxPlayers
                : MAX_PLAYERS_OPTIONS.get(MAX_PLAYERS_OPTIONS.size() - 1);
        int[] mpIndex = {Math.max(0, MAX_PLAYERS_OPTIONS.indexOf(initialMaxPlayers))};
        Button maxPlayersButton = new Button(left, y + 26, 150, 20,
                maxPlayersLabel(MAX_PLAYERS_OPTIONS.get(mpIndex[0])), b -> {
            mpIndex[0] = (mpIndex[0] + 1) % MAX_PLAYERS_OPTIONS.size();
            int value = MAX_PLAYERS_OPTIONS.get(mpIndex[0]);
            PeerCraftHostOptions.maxPlayers = value;
            b.setMessage(maxPlayersLabel(value));
        });

        CallbackCheckbox allowUnlicensedCheckbox = new CallbackCheckbox(left, y + 52, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.allow_unlicensed"),
                PeerCraftHostOptions.allowUnlicensedPlayers,
                value -> PeerCraftHostOptions.allowUnlicensedPlayers = value);

        EditBox worldNameBox = new EditBox(this.font, left, y + 130, 150, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.world_name"));
        worldNameBox.setMaxLength(PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH);
        worldNameBox.setSuggestion(new TranslatableComponent("peercraft.mixin.share_to_lan.world_name_hint").getString());
        worldNameBox.setValue(PeerCraftHostOptions.worldName);
        worldNameBox.setResponder(value -> {
            PeerCraftHostOptions.worldName = value;
            worldNameBox.setSuggestion(value.isEmpty()
                    ? new TranslatableComponent("peercraft.mixin.share_to_lan.world_name_hint").getString()
                    : "");
        });

        boolean loggedIn = net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession() != null;

        // friendsOnlyHolder breaks the circular reference between the two mutually-exclusive
        // checkbox callbacks — same trick as the original.
        CallbackCheckbox[] friendsOnlyHolder = new CallbackCheckbox[1];

        CallbackCheckbox publicRoomCheckbox = new CallbackCheckbox(left, y + 104, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.public_room"),
                PeerCraftHostOptions.publicRoom,
                value -> {
                    PeerCraftHostOptions.publicRoom = value;
                    worldNameBox.visible = value && PeerCraftHostOptions.internetPlayRequested;
                    if (friendsOnlyHolder[0] != null) {
                        friendsOnlyHolder[0].active = !value && loggedIn;
                    }
                });

        CallbackCheckbox friendsOnlyCheckbox = new CallbackCheckbox(left, y + 78, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.friends_only"),
                PeerCraftHostOptions.friendsOnly,
                value -> {
                    PeerCraftHostOptions.friendsOnly = value;
                    publicRoomCheckbox.active = !value;
                });
        friendsOnlyCheckbox.active = loggedIn && !PeerCraftHostOptions.publicRoom;
        publicRoomCheckbox.active = !PeerCraftHostOptions.friendsOnly;
        friendsOnlyHolder[0] = friendsOnlyCheckbox;

        maxPlayersButton.visible = PeerCraftHostOptions.internetPlayRequested;
        allowUnlicensedCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        friendsOnlyCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        publicRoomCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;
        worldNameBox.visible = PeerCraftHostOptions.internetPlayRequested && PeerCraftHostOptions.publicRoom;

        CallbackCheckbox internetCheckbox = new CallbackCheckbox(left, y, 20, 20,
                new TranslatableComponent("peercraft.mixin.share_to_lan.internet_play"),
                PeerCraftHostOptions.internetPlayRequested,
                value -> {
                    PeerCraftHostOptions.internetPlayRequested = value;
                    maxPlayersButton.visible = value;
                    allowUnlicensedCheckbox.visible = value;
                    friendsOnlyCheckbox.visible = value;
                    publicRoomCheckbox.visible = value;
                    worldNameBox.visible = value && PeerCraftHostOptions.publicRoom;
                });

        this.addButton(internetCheckbox);
        this.addButton(maxPlayersButton);
        this.addButton(allowUnlicensedCheckbox);
        this.addButton(friendsOnlyCheckbox);
        this.addButton(publicRoomCheckbox);
        this.addButton(worldNameBox);
    }

    private static Component maxPlayersLabel(int value) {
        return new TranslatableComponent("peercraft.mixin.share_to_lan.max_players")
                .append(new TextComponent(": " + value));
    }

    /** {@link Checkbox} with an after-toggle callback — 1.16.5 has no {@code onValueChange}. */
    private static final class CallbackCheckbox extends Checkbox {
        private final Consumer<Boolean> onChange;

        CallbackCheckbox(int x, int y, int width, int height, Component message, boolean selected, Consumer<Boolean> onChange) {
            super(x, y, width, height, message, selected);
            this.onChange = onChange;
        }

        @Override
        public void onPress() {
            super.onPress();
            onChange.accept(this.selected());
        }
    }
}
