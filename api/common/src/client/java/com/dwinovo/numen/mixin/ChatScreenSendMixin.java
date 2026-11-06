package com.dwinovo.numen.mixin;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.chat.CompanionChatScreen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原版聊天栏里对她说话的两条路,都拦在 {@code ChatScreen#handleChatInput} 的提交入口上:
 * <ul>
 *   <li><b>提到她就接(主路径)</b>:普通聊天文本里出现 {@code yuuka}(不区分大小写)或 {@code 优香}
 *       ——这句本来就是公屏消息,<b>不 cancel</b>,原版照常发送,只是把同一句正文也喂给
 *       {@link CompanionChatScreen#submit}(与 Y 屏同一条 owner-message 管线)。
 *       没命中就什么都不做——不唤醒、不回话,这是"别插话"的保证;</li>
 *   <li><b>{@code /yuuka <话>}(显式写法)</b>:{@code /} 开头会被原版当命令交给服务端解析,这台机器
 *       装了 430 个模组,{@code yuuka} 这个命令名很可能已被谁占,注册真命令会撞名。所以在提交入口
 *       截住:命中前缀 → {@code cancel}(服务端永远看不到这条"命令")→ 去掉前缀的正文用
 *       <b>原版正常聊天出口</b>再发一次({@code mc.player.connection.sendChat},签名链由原版生成,
 *       不手拼广播包)→ 同一句再喂 owner-message 管线。</li>
 * </ul>
 *
 * <p>命令({@code /} 开头且不是 {@code /yuuka})一律不看;{@code /yuuka} 裸打(正文 trim 后为空)
 * 只在本地聊天栏回一行灰色提示,不发送(本地 {@code addMessage} 不出网);正文去前缀后以 {@code /}
 * 开头的不发公屏(避免被当命令再解析一次),只私下告诉她。
 *
 * <p>识别前先过一遍原版自己的 {@link #normalizeChatMessage}(trim → 归并空白 → 256 字符截断;
 * 原版对 § 格式码不做处理,这里的正文与手输普通聊天逐字节同待遇),
 * 保证"我们看到的就是原版将要发的那一句",前后空格、全角空白都不会让判定走样。
 * 每次命中记一条触发日志(谁、走的哪条路、命中的词),便于事后核对有没有漏接/多接。
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenSendMixin {

    /** 带正文的 {@code /yuuka <话>}或裸 {@code /yuuka}(捕获组为 null);大小写不敏感。 */
    private static final Pattern NUMEN_YUUKA_PREFIX =
            Pattern.compile("^/yuuka(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE);
    /** 普通聊天里的"提到她":词出现即接,但前后不得贴 ASCII 字母数字——「Myyuuka」是别人的名字,
     * 她不插话;中文贴字({@code 你说yuuka了})不受影响。{@code 优香}无大小写,同一开关无害。 */
    private static final Pattern NUMEN_MENTION =
            Pattern.compile("(?<![A-Za-z0-9])yuuka(?![A-Za-z0-9])|优香", Pattern.CASE_INSENSITIVE);

    /** 原版提交前的同一道工序(trim → 归并空白 → 256 字符截断,见原版
     *  {@code StringUtil#truncateStringIfNecessary};<b>不剥 § 格式码</b>),识别与原版将要发的对齐。 */
    @Shadow
    public abstract String normalizeChatMessage(String message);

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void numen$yuukaSubmit(String raw, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        String text = this.normalizeChatMessage(raw == null ? "" : raw);

        Matcher prefix = NUMEN_YUUKA_PREFIX.matcher(text);
        if (prefix.matches()) {
            // 拦下原版提交(sendCommand 与 addRecentChat 都不碰),返回 false 让 keyPressed 不再重复关屏
            cir.setReturnValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(null);
            String body = prefix.group(1) == null ? "" : prefix.group(1);
            if (body.isEmpty()) {
                // 只在本地聊天栏回一行提示,不发送
                numen$hint("/yuuka <要说什么>");
                return;
            }
            if (mc.player != null && !body.startsWith("/")) {
                // 主人的话也要公屏:去掉前缀的正文走原版正常聊天出口再发一次(签名链由原版生成);
                // 不手拼广播包——1.20.1 的聊天有签名,手搓的包会被判未签名/重放
                mc.player.connection.sendChat(body);
            }
            Constants.LOG.info("[yuuka-trigger] via=/yuuka owner={} body={}", mc.getUser().getName(), body);
            // 同一句喂给 owner-message 管线(与 Y 屏同一条路);正文以 / 开头时上面没发公屏,只私下告诉她
            CompanionChatScreen.submit(body);
            return;
        }

        if (text.startsWith("/")) {
            return;   // 其它命令(/say、/gamemode…)一律不看,一个字节都不碰
        }

        Matcher mention = NUMEN_MENTION.matcher(text);
        if (mention.find()) {
            // 主路径:提到她才接。不 cancel——这句本来就是公屏消息,原版照常发出(addRecentChat、
            // sendChat、关屏都不动);只是把同一条正文也喂给 owner-message 管线
            Constants.LOG.info("[yuuka-trigger] via=mention keyword={} owner={} text={}",
                    mention.group(), Minecraft.getInstance().getUser().getName(), text);
            CompanionChatScreen.submit(text);
        }
        // 没命中:什么都不做——不唤醒、不回话
    }

    /** 只在本地聊天栏回一行灰色提示;本地 {@code addMessage} 不出网。 */
    private static void numen$hint(String message) {
        Minecraft.getInstance().gui.getChat().addMessage(
                Component.literal(message).withStyle(ChatFormatting.GRAY));
    }
}
