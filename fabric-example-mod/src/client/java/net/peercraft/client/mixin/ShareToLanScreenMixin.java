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

    // Список значений для степпера "Макс. игроков" — сервер знакомств клампит независимо
    // до [1,32] (см. RoomRegistry), но для реального использования (комната для друзей)
    // хватает небольшого набора значений в GUI, не полного диапазона.
    private static final List<Integer> MAX_PLAYERS_OPTIONS = IntStream.rangeClosed(1, 8).boxed().collect(Collectors.toList());

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addInternetCheckbox(CallbackInfo ci) {
        this.addRenderableWidget(Checkbox.builder(Component.literal("PeerCraft: через интернет"), this.font)
                .pos(this.width / 2 - 155, this.height - 54)
                .selected(PeerCraftHostOptions.internetPlayRequested)
                .onValueChange((checkbox, value) -> PeerCraftHostOptions.internetPlayRequested = value)
                .build());

        int initialMaxPlayers = MAX_PLAYERS_OPTIONS.contains(PeerCraftHostOptions.maxPlayers) ? PeerCraftHostOptions.maxPlayers : MAX_PLAYERS_OPTIONS.get(MAX_PLAYERS_OPTIONS.size() - 1);
        this.addRenderableWidget(CycleButton.<Integer>builder(value -> Component.literal(String.valueOf(value)))
                .withValues(MAX_PLAYERS_OPTIONS)
                .withInitialValue(initialMaxPlayers)
                .create(this.width / 2 + 5, this.height - 54, 150, 20, Component.literal("Макс. игроков"),
                        (button, value) -> PeerCraftHostOptions.maxPlayers = value));

        this.addRenderableWidget(Checkbox.builder(Component.literal("Разрешить нелицензионных игроков"), this.font)
                .pos(this.width / 2 - 155, this.height - 30)
                .selected(PeerCraftHostOptions.allowUnlicensedPlayers)
                .onValueChange((checkbox, value) -> PeerCraftHostOptions.allowUnlicensedPlayers = value)
                .build());
    }
}
