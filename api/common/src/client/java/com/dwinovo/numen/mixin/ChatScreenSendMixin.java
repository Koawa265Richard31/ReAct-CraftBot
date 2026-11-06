package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.chat.CompanionChatScreen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在原版聊天栏直接对她说话:{@code /yuuka <话>}(大小写不敏感,如 {@code /Yuuka})。
 *
 * <p>为什么拦输入而不注册命令:{@code /} 开头的文本原版照 {@code ChatScreen#handleChatInput}
 * 的字节码分派——带斜杠就走 {@code sendCommand} 交给服务端解析,这台机器装了 430 个模组,
 * {@code yuuka} 这个命令名很可能已被谁占,注册真命令会撞名。所以在提交入口截住:
 * 命中前缀 → 原版一句都不发(服务端永远看不到这条"命令")→ 关屏 → 正文交
 * {@link CompanionChatScreen#submit},与 Y 屏走同一条说话路。
 *
 * <p>正文为空({@code /yuuka} 裸打,或后面全是空白)不发送,只在本地聊天栏回一行灰色提示
 * ——本地 {@code addMessage} 不出网。其它命令({@code /say}、{@code /gamemode}…)与普通聊天
 * 不匹配前缀,原样放行,一个字节都不碰。
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenSendMixin {

    /** 带正文的 {@code /yuuka <话>};正文 trim 后判空。 */
    private static final Pattern NUMEN_YUUKA_WORD = Pattern.compile("^\\s*/yuuka\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    /** 裸的 {@code /yuuka}(或后面全是空白):拦下、只提示,别让它落进 sendCommand 变"未知命令"。 */
    private static final Pattern NUMEN_YUUKA_BARE = Pattern.compile("^\\s*/yuuka\\s*$", Pattern.CASE_INSENSITIVE);

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void numen$yuukaSubmit(String raw, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        String text = raw == null ? "" : raw;
        Matcher word = NUMEN_YUUKA_WORD.matcher(text);
        if (!word.matches() && !NUMEN_YUUKA_BARE.matcher(text).matches()) {
            return;   // 不是对她说的:原版发送/补全/历史一个行为都不改
        }
        // 拦下原版提交(addRecentChat 与 sendCommand 都不碰),返回 false 让 keyPressed 不再重复关屏
        cir.setReturnValue(false);
        Minecraft.getInstance().setScreen(null);
        String body = word.matches() ? word.group(1).trim() : "";
        if (body.isEmpty()) {
            // 只在本地聊天栏回一行提示,不发送
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal("/yuuka <要说什么>")
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY.getColor())));
            return;
        }
        CompanionChatScreen.submit(body);
    }
}
