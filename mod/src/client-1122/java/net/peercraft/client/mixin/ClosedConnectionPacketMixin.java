package net.peercraft.client.mixin;

import com.google.common.util.concurrent.ListenableFuture;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.util.IThreadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Queued play packets must not access a world after their connection has closed. */
@Mixin(PacketThreadUtil.class)
public abstract class ClosedConnectionPacketMixin {
    @Redirect(method = "checkThreadAndEnqueue", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/IThreadListener;addScheduledTask(Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;"))
    private static ListenableFuture<Object> peercraft$guardClosedConnection(
            IThreadListener scheduler, Runnable task, Packet<?> packet,
            INetHandler processor, IThreadListener originalScheduler) {
        return scheduler.addScheduledTask(() -> {
            if (processor instanceof NetHandlerPlayClient
                    && !((NetHandlerPlayClient) processor).getNetworkManager().isChannelOpen()) return;
            task.run();
        });
    }
}
