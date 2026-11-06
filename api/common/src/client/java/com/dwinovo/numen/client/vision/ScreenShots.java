package com.dwinovo.numen.client.vision;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.NumenPaths;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 抓当前客户端画面存成 PNG,落进 {@code config/numen/shots/}。
 *
 * <p>这是"她能看见眼前"的那只眼:主人说看、她卡住、开打之前,抓一张缩略图比
 * 几十行坐标文本有用得多。给模型的永远是<b>文件位置</b>,不是图本身——几十 KB 的
 * base64 塞进上下文,这一轮就什么都干不了了。
 *
 * <h2>线程</h2>
 * framebuffer 只能在渲染线程上读({@link Screenshot#takeScreenshot} 里的
 * {@code downloadTexture} 自己就会 {@code assertOnRenderThread})。所以
 * {@link #grab} 在别处调用时先排队到客户端线程——1.20.1 的客户端线程就是渲染线程
 * (见 {@code Main.main} 里的 {@code Thread.setName("Render thread")} 与
 * {@code RenderSystem.initRenderThread}),排过去就等于排到渲染线程。
 */
public final class ScreenShots {

    /** 缩略图默认尺寸:够看清地形与生物,又不会把图撑大。 */
    public static final int DEFAULT_WIDTH = 320;
    public static final int DEFAULT_HEIGHT = 180;
    /** 全分辨率截图的上限——再大对模型没有意义,只在磁盘上占地。 */
    public static final int MAX_WIDTH = 1280;
    public static final int MAX_HEIGHT = 720;
    /** 只留最近这么多张(vision 配置的 keepShots;调用处没给就用它):目录是给"刚才发生了什么"看的,不是相册。 */
    public static final int DEFAULT_KEEP_SHOTS = 10;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** 一张落盘的图:位置、实际尺寸、字节数——模型需要的全部。 */
    public record Shot(Path path, int width, int height, long bytes) {}

    private ScreenShots() {}

    /** {@code config/numen/shots/};不保证已存在,写之前自己建。 */
    public static Path dir() {
        return NumenPaths.config().resolve("shots");
    }

    /**
     * 抓一张。调用方多半在 HTTP 线程上,这里负责把它挪到渲染线程并等结果;
     * 已经站在渲染线程上就直接做,免得排队后自己等自己。
     *
     * <p>失败(没进世界、窗口没有 framebuffer、写盘出错)都以可读异常收在 future 里。
     */
    public static CompletableFuture<Shot> grab(int width, int height) {
        return grab(width, height, DEFAULT_KEEP_SHOTS);
    }

    /**
     * 同 {@link #grab(int,int)},另带清理上限 {@code keepShots}——vision 配置的
     * {@code keepShots}(本类读不到那份配置,由调用处透传);非正数按 {@link #DEFAULT_KEEP_SHOTS}。
     */
    public static CompletableFuture<Shot> grab(int width, int height, int keepShots) {
        int keep = keepShots <= 0 ? DEFAULT_KEEP_SHOTS : keepShots;
        CompletableFuture<Shot> done = new CompletableFuture<>();
        Runnable job = () -> {
            try {
                done.complete(capture(clamp(width, DEFAULT_WIDTH, MAX_WIDTH),
                        clamp(height, DEFAULT_HEIGHT, MAX_HEIGHT), keep));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        };
        if (RenderSystem.isOnRenderThread()) {
            job.run();                       // 已在渲染线程:直接读,不排队
        } else {
            Minecraft client = Minecraft.getInstance();
            client.execute(job);             // 排队到客户端线程(= 渲染线程)
        }
        return done;
    }

    /** 渲染线程上:抓全图 → 缩到目标尺寸 → 写 PNG → 清旧图。 */
    private static Shot capture(int width, int height, int keep) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            throw new IllegalStateException(
                    "not in a world — there is nothing to capture; join a world first");
        }
        if (mc.getMainRenderTarget().width <= 0 || mc.getMainRenderTarget().height <= 0) {
            throw new IllegalStateException("the game window has no framebuffer to capture");
        }
        NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget());   // 已是正的
        NativeImage small = null;
        try {
            small = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
            Path file = nextFile();
            Files.createDirectories(file.getParent());
            small.writeToFile(file);
            prune(file.getParent(), keep);
            return new Shot(file, width, height, Files.size(file));
        } finally {
            full.close();
            if (small != null) small.close();
        }
    }

    /** 时间戳做文件名:天然不撞名(毫秒粒度);清理按 mtime 排,不靠名字。 */
    private static Path nextFile() {
        String name = LocalDateTime.now().format(STAMP) + ".png";
        return dir().resolve(name);
    }

    /**
     * 成功写完一张后同步清一次:列目录、按 mtime 旧→新排、删掉超出 {@code keep} 的最旧几张。
     * 就在调用线程(渲染线程)上做,不开线程、不开定时器;列不出/读不到 mtime/删不动都只记日志,
     * 不影响刚写好的这一张。
     */
    private static void prune(Path dir, int keep) {
        List<Path> shots;
        try (Stream<Path> list = Files.list(dir)) {
            shots = new ArrayList<>(list.filter(p -> p.getFileName().toString().endsWith(".png")).toList());
        } catch (IOException e) {
            Constants.LOG.warn("[numen] couldn't list {} for cleanup", dir, e);
            return;
        }
        // mtime 旧→新;有文件读不到属性就整批放弃这次清理——宁可不删,不误删刚写的那张
        Map<Path, Long> mtimes = new HashMap<>();
        try {
            for (Path p : shots) mtimes.put(p, Files.getLastModifiedTime(p).toMillis());
        } catch (IOException e) {
            Constants.LOG.warn("[numen] couldn't read timestamps in {} for cleanup", dir, e);
            return;
        }
        shots.sort(Comparator.comparing(mtimes::get));
        for (int i = 0; i < shots.size() - keep; i++) {
            try {
                Files.deleteIfExists(shots.get(i));
            } catch (IOException e) {
                Constants.LOG.warn("[numen] couldn't delete old screenshot {}", shots.get(i), e);
            }
        }
    }

    /** 没给或给了个非正数就按默认来,超上限就压到上限。 */
    private static int clamp(int value, int fallback, int max) {
        if (value <= 0) return fallback;
        return Math.min(value, max);
    }
}
