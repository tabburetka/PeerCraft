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
        net.minecraft.client.gui.screens.Screen current=net.minecraft.client.Minecraft.getInstance().screen;
        if(current==null)return;
        String base=new net.minecraft.network.chat.TranslatableComponent("peercraft.admission.menu").getString(),shortLabel=new net.minecraft.network.chat.TranslatableComponent("peercraft.admission.menu_short").getString();
        net.peercraft.network.admission.AdmissionClient client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        for(net.minecraft.client.gui.components.events.GuiEventListener child:current.children())if(child instanceof net.minecraft.client.gui.components.Button){
            net.minecraft.client.gui.components.Button button=(net.minecraft.client.gui.components.Button)child;String label=button.getMessage().getString();
            if(label.startsWith(base))button.setMessage(new net.minecraft.network.chat.TextComponent(base+" · "+client.entries().size()));
            if(label.startsWith(shortLabel))button.setMessage(new net.minecraft.network.chat.TextComponent(shortLabel+(client.waiting()?" · "+new net.minecraft.network.chat.TranslatableComponent(client.guestStatus==2?"peercraft.admission.ready_short":"peercraft.admission.pending_short").getString():"")));
        }
    }
}
