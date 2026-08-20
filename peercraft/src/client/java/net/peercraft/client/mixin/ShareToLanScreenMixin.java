package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.PeerCraftHostOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Mixin(ShareToLanScreen.class)
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
        int y = this.height - 104;

        int initialMaxPlayers = MAX_PLAYERS_OPTIONS.contains(PeerCraftHostOptions.maxPlayers) ? PeerCraftHostOptions.maxPlayers : MAX_PLAYERS_OPTIONS.get(MAX_PLAYERS_OPTIONS.size() - 1);
        CycleButton<Integer> maxPlayersButton = CycleButton.<Integer>builder(value -> Component.literal(String.valueOf(value)))
                .withValues(MAX_PLAYERS_OPTIONS)
                .withInitialValue(initialMaxPlayers)
                .create(this.width / 2 - 155, y + 26, 150, 20, Component.translatable("peercraft.mixin.share_to_lan.max_players"),
                        (button, value) -> PeerCraftHostOptions.maxPlayers = value);

        Checkbox allowUnlicensedCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.allow_unlicensed"), this.font)
                .pos(this.width / 2 - 155, y + 52)
                .selected(PeerCraftHostOptions.allowUnlicensedPlayers)
                .onValueChange((checkbox, value) -> PeerCraftHostOptions.allowUnlicensedPlayers = value)
                .build();

        // Both only make sense when hosting through PeerCraft's internet path at all — hidden
        // (not just disabled) whenever that's off, and re-shown live as the checkbox toggles.
        maxPlayersButton.visible = PeerCraftHostOptions.internetPlayRequested;
        allowUnlicensedCheckbox.visible = PeerCraftHostOptions.internetPlayRequested;

        Checkbox internetCheckbox = Checkbox.builder(Component.translatable("peercraft.mixin.share_to_lan.internet_play"), this.font)
                .pos(this.width / 2 - 155, y)
                .selected(PeerCraftHostOptions.internetPlayRequested)
                .onValueChange((checkbox, value) -> {
                    PeerCraftHostOptions.internetPlayRequested = value;
                    maxPlayersButton.visible = value;
                    allowUnlicensedCheckbox.visible = value;
                })
                .build();

        this.addRenderableWidget(internetCheckbox);
        this.addRenderableWidget(maxPlayersButton);
        this.addRenderableWidget(allowUnlicensedCheckbox);
    }
}
