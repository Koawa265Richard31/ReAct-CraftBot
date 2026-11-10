package com.dwinovo.numen.platform;

import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单测的网络通道:登记照单全收,发送按方向记进 {@link #SENT}(S→C,服务端回执)与
 * {@link #SENT_C2S}(C→S,客户端面板发的请求),测试从那里读真的发出去的载荷。
 * 顺着类路径上的 service 文件被 {@code Services.NETWORK} 装上。
 */
public final class TestNetworkChannel implements com.dwinovo.numen.platform.services.INetworkChannel {

    /** 服务端发出的下行载荷,按发送顺序。测试开头自己清。 */
    public static final List<NumenPayload> SENT = new ArrayList<>();

    /** 客户端发出的上行载荷,按发送顺序(面板角色页回执的后续动作:状态请求、容器重读)。
     * 测试开头自己清。 */
    public static final List<NumenPayload> SENT_C2S = new ArrayList<>();

    @Override
    public <T extends NumenPayload> void registerClientToServer(
            ResourceLocation id, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
    }

    @Override
    public <T extends NumenPayload> void registerServerToClient(
            ResourceLocation id, Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {
    }

    @Override
    public void sendToServer(NumenPayload payload) {
        SENT_C2S.add(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, NumenPayload payload) {
        SENT.add(payload);
    }
}
