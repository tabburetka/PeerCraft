package net.peercraft.client.gui;
public final class AdmissionScreens {
    private AdmissionScreens() { }
    public static void join(net.minecraft.client.gui.screens.Screen owner,String room,Runnable proceed) {
        net.peercraft.network.admission.AdmissionClient client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        if(net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession()==null){proceed.run();return;}
        if(!client.waiting())client.request(room,()->net.minecraft.client.Minecraft.getInstance().execute(()->{
            PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getInstance(),owner);proceed.run();
        }));
        PeerCraftUi.setScreen(net.minecraft.client.Minecraft.getInstance(),new JoinRequestWaitingScreen(owner));
    }

    public static void tick(){
        //? if <26.2
        net.minecraft.client.gui.screens.Screen current=net.minecraft.client.Minecraft.getInstance().screen;
        //? if >=26.2
        /*net.minecraft.client.gui.screens.Screen current=net.minecraft.client.Minecraft.getInstance().gui.screen();*/
        if(current==null)return;
        String base=net.minecraft.network.chat.Component.translatable("peercraft.admission.menu").getString(),shortLabel=net.minecraft.network.chat.Component.translatable("peercraft.admission.menu_short").getString();
        net.peercraft.network.admission.AdmissionClient client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        for(net.minecraft.client.gui.components.events.GuiEventListener child:current.children())if(child instanceof net.minecraft.client.gui.components.Button){
            net.minecraft.client.gui.components.Button button=(net.minecraft.client.gui.components.Button)child;String label=button.getMessage().getString();
            if(label.startsWith(base))button.setMessage(net.minecraft.network.chat.Component.literal(base+" · "+client.entries().size()));
            if(label.startsWith(shortLabel))button.setMessage(net.minecraft.network.chat.Component.literal(shortLabel+(client.waiting()?" · "+net.minecraft.network.chat.Component.translatable(client.guestStatus==2?"peercraft.admission.ready_short":"peercraft.admission.pending_short").getString():"")));
        }
    }
}
