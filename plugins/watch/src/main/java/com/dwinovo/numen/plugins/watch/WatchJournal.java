package com.dwinovo.numen.plugins.watch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * watch 的持久事件日志:append-only JSONL,一行一条、一个 JSON 对象,固定八字段
 * {@code {seq, tick, type, dim, pos, subject, source, note}}(schema 是 monitor §五 v1.1 与 spatial
 * 共用的那一套;watch 行的 dim/pos 暂留空串 —— WatchSnapshot 不携带维度与格子文本,等与 spatial
 * 共用同一份日志时对齐)。DSH 按水位(lastEventSeq)拉增量;mod 侧只追加,永不消费式清空,
 * <b>零新网络面</b>(不开端口、不出网)。
 *
 * <h2>seq 跨重启续号</h2>
 * {@code seq} 单调递增、由本类发放;<b>不用 tick 当 seq</b>(重启 tick 归零)。构造时把整本扫一遍,
 * 取盘上最大 seq,下一个从它之后接着数 —— 日志文件本身就是续号的持久化。文件被删等于台账重开
 * (seq 从 1 重新起),消费方按水位重读即可,不丢已发生的事之外的东西。
 *
 * <h2>compaction</h2>
 * 行数超过 {@link #COMPACT_KEEP}(200,照 {@code agent/.../EventQueue.java} 的 {@code DEFAULT_CAP = 200}
 * 家法数字)时整本快照重写:保留最新 {@code COMPACT_KEEP - 1} 行数据,再追加一条
 * {@code type=compaction} 记账行(写明被丢弃的 seq 区间,照 EventQueue 的 droppedNote 家法:
 * 排空时如实补一句)—— 压缩后整本恰 {@value #COMPACT_KEEP} 行,再涨过这条线才再压。
 * 已知代价照实写:DSH 落后超过一整本时,最老的未读行被压缩丢掉,靠记账行知情。
 *
 * <h2>容错与线程(照 JsonlJournal 的家法)</h2>
 * 坏行跳过、读不了当空箱、写失败只记日志不打断任务;纯 JVM(Gson + nio),不碰 Minecraft:路径由
 * 调用方解析后注入({@code NumenApi.configDir()},文件名 {@code numen_watch-events.jsonl})。
 * 主线程唯一写者(求值与命中都在主线程),方法上不加锁,只做可见性兜底。
 */
public final class WatchJournal {

    private static final Logger LOG = LoggerFactory.getLogger("numen-watch");

    /** 压缩线(整本压缩后保留的行数,含一条记账行):200,照 EventQueue.DEFAULT_CAP 的家法数字。 */
    static final int COMPACT_KEEP = 200;
    /** 记账行的类型名(monitor §五 v1.1 的四类之一)。 */
    static final String COMPACTION_TYPE = "compaction";

    private final Path file;
    /** 下一个要发的 seq(= 盘上最大 seq + 1)。 */
    private long nextSeq = 1;
    /** 当前行数(压缩判定用)。 */
    private int lines;

    private WatchJournal(Path file) {
        this.file = file;
    }

    /** 指到文件就开一本;构造时扫一遍续号(坏行跳过、读不了当空箱)。 */
    public static WatchJournal atFile(Path file) {
        WatchJournal j = new WatchJournal(file);
        j.scan();
        return j;
    }

    private void scan() {
        nextSeq = 1;
        lines = 0;
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (o.has("seq")) {
                        nextSeq = Math.max(nextSeq, o.get("seq").getAsLong() + 1);
                    }
                    lines++;
                } catch (RuntimeException bad) {
                    LOG.warn("[numen-watch] 跳过事件日志坏行 {}", file.getFileName());
                }
            }
        } catch (IOException ex) {
            LOG.warn("[numen-watch] 读不了 {}: {} — 当空箱续号", file.getFileName(), ex.getMessage());
            nextSeq = 1;
            lines = 0;
        }
    }

    /** 已发到的最大 seq(续号从它之后接着数;单测读)。 */
    public long lastSeq() {
        return nextSeq - 1;
    }

    /** 当前行数(单测读)。 */
    public int size() {
        return lines;
    }

    /**
     * 追加一行(主线程调用)。写失败只记日志:事件日志出问题不该让求值与喊停停摆(JsonlJournal 家法)。
     */
    public synchronized void append(long tick, String type, String dim, String pos, String subject,
                                    String source, String note) {
        JsonObject o = new JsonObject();
        long seq = nextSeq;
        o.addProperty("seq", seq);
        o.addProperty("tick", tick);
        o.addProperty("type", type);
        o.addProperty("dim", dim);
        o.addProperty("pos", pos);
        o.addProperty("subject", subject);
        o.addProperty("source", source);
        o.addProperty("note", note);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            nextSeq = seq + 1;
            lines++;
            if (lines > COMPACT_KEEP) {
                compact();
            }
        } catch (IOException ex) {
            LOG.warn("[numen-watch] 事件日志写不了 {}: {}", file.getFileName(), ex.getMessage());
        }
    }

    /** 整本快照重写:保留最新 COMPACT_KEEP - 1 行数据,追加一条记账行;seq 计数不动。 */
    private void compact() {
        List<Line> all = read();
        all.sort(Comparator.comparingLong(l -> l.seq));
        int keep = Math.min(COMPACT_KEEP - 1, all.size());
        List<Line> kept = all.subList(all.size() - keep, all.size());
        long droppedFrom = all.isEmpty() || kept.isEmpty() ? 0 : all.get(0).seq;
        long droppedTo = kept.isEmpty() ? (all.isEmpty() ? 0 : all.get(all.size() - 1).seq)
                : kept.get(0).seq - 1;
        StringBuilder sb = new StringBuilder();
        for (Line l : kept) {
            sb.append(l.json).append('\n');
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("seq", nextSeq);
        marker.addProperty("tick", 0L);
        marker.addProperty("type", COMPACTION_TYPE);
        marker.addProperty("dim", "");
        marker.addProperty("pos", "");
        marker.addProperty("subject", file.getFileName() == null ? "events" : file.getFileName().toString());
        marker.addProperty("source", "watch");
        marker.addProperty("note", "compaction kept seq " + (kept.isEmpty() ? 0 : kept.get(0).seq) + ".."
                + (kept.isEmpty() ? 0 : kept.get(kept.size() - 1).seq) + "; dropped seq " + droppedFrom + ".."
                + droppedTo);
        sb.append(marker).append('\n');
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            lines = COMPACT_KEEP;
            nextSeq++;
            LOG.info("[numen-watch] 事件日志压缩:保留最新 {} 行,丢弃 seq {}..{},记账行 seq {}",
                    keep, droppedFrom, droppedTo, nextSeq - 1);
        } catch (IOException ex) {
            LOG.warn("[numen-watch] 事件日志压缩失败 {}: {}", file.getFileName(), ex.getMessage());
        }
    }

    /** 读全本(坏行跳过);只有压缩时用,不是热路径。 */
    private List<Line> read() {
        List<Line> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (o.has("seq")) {
                        out.add(new Line(o.get("seq").getAsLong(), line));
                    }
                } catch (RuntimeException bad) {
                    LOG.warn("[numen-watch] 压缩时跳过坏行 {}", file.getFileName());
                }
            }
        } catch (IOException ex) {
            LOG.warn("[numen-watch] 压缩读不了 {}: {}", file.getFileName(), ex.getMessage());
        }
        return out;
    }

    private record Line(long seq, String json) {}
}
