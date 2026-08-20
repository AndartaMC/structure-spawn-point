package eiruna.structure.spawn.point;

import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class Message {
    public static void send(ServerPlayerEntity player, String title) {
        if(title != null && !title.isBlank()){
            if(StructureSpawnPoint.CONFIG.send_popup_messages) {
                player.networkHandler.sendPacket(new TitleS2CPacket(
                        Text.of(title)
                ));
                player.networkHandler.sendPacket(new SubtitleS2CPacket(
                        Text.of("")
                ));
            }
            if(StructureSpawnPoint.CONFIG.send_chat_messages)
            {
                player.sendMessage(Text.of(title));
            }
        }
    }

    public static void send(ServerPlayerEntity player, String title, String subTitle) {
        if(title != null && !title.isBlank() && subTitle != null && !subTitle.isBlank()){
            if(StructureSpawnPoint.CONFIG.send_popup_messages) {
                player.networkHandler.sendPacket(new TitleS2CPacket(
                        Text.of(title)
                ));
                player.networkHandler.sendPacket(new SubtitleS2CPacket(
                        Text.of(subTitle)
                ));
            }
            if(StructureSpawnPoint.CONFIG.send_chat_messages)
            {
                player.sendMessage(Text.of(title));
                player.sendMessage(Text.of(subTitle));
            }
            return;
        }
        if(title != null && !title.isBlank()) {
            send(player, title);
            return;
        }
        if(subTitle != null && !subTitle.isBlank()){
            send(player, subTitle);
        }
    }
}
