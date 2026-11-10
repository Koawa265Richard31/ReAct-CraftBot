package com.dwinovo.numen.plugins.watch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 反射白名单:主人逐条批准过的「命中 → 自动起步」动作表。<b>默认空</b> —— 白名单上没有任何动作时,
 * 订阅声明的 {@code action} 一律不起步:命中只停车(打断当前程序),回执与事件里写明
 * 「白名单未批准,只停车」(拍板口径 2026-11-07)。
 *
 * <h2>它管住什么</h2>
 * 白名单的条目不是动作的<b>名字</b>,而是动作<b>文本的 SHA-256</b>({@link #idOf}):批准的是这一段
 * Lua 本身,不是任何一个想借这个名字的东西。订阅命中时引擎算出它声明的动作文本的哈希、来这张表查;
 * 表里没有 → 不起步。改一个字符就是另一个哈希 —— 她批不了自己,外脑也换不掉已批准的动作。
 *
 * <h2>持久化与读写(照仓库已有的配置/权限写法,不自创)</h2>
 * <ul>
 *   <li><b>在哪</b>:{@code config/numen/} 下的 {@code numen_watch-whitelist.json} ——
 *       {@code NumenApi.configDir()} 的文档约定就是「引擎和插件共用的配置目录,插件的持久数据放这儿,
 *       文件名带上自己的 id」(如 providers.json / skin.json 同一家法);路径由装配方解析后注入,
 *       本类不碰 Minecraft。</li>
 *   <li><b>怎么读写</b>:启动读一遍;之后每次 {@link #allows} 前看一眼文件 mtime,变过就重读
 *       (主人改文件即时生效,不用重启);唯一的写入口是 {@link #grant}/{@link #revoke}(主人的
 *       命令面与面板调它;v0 的载体是这份文件本身)。写失败只记日志不打断(照 JsonlJournal 家法)。</li>
 *   <li><b>默认空</b>:文件不存在、读不了、内容不合法,都是空表 —— 空表 = 只停车。</li>
 *   <li><b>谁有权改</b>:主人。Lua/外脑面没有任何写入口 —— {@code numen.watch.*} 只有
 *       add/list/drop/events 四个函数,她在自己的 API 里批不了自己的动作;条目要么主人手编这份
 *       配置文件(与 providers.json 同一家法),要么经主人的命令面调 {@link #grant}
 *       (命令树挂载是 core 侧的活,见 WIRING.md 第 8 节)。</li>
 * </ul>
 *
 * <h2>文件形状</h2>
 * <pre>{@code
 * { "allowed": [ { "id": "<64 个十六进制字符,动作文本的 SHA-256>", "note": "主人自己写的备注" } ] }
 * }</pre>
 * 逐条读:id 不是 64 位十六进制的行记一条警告、不进表(照 PermissionStore 逐行读档的家法),
 * 其余照读。
 */
public final class WatchWhitelist {

    private static final Logger LOG = LoggerFactory.getLogger("numen-watch");

    private final Path file;
    /** 在册的动作(默认空;每次改动换一份新的不可变表,读方拿走的是完整快照)。 */
    private List<Entry> allowed = List.of();
    /** 上次读文件时的 mtime;-1 = 还没读过,0 = 文件不存在过。 */
    private long stamp = -1;

    /** 一条批准记录:动作文本的 SHA-256 与主人的备注。 */
    public record Entry(String id, String note) {}

    private WatchWhitelist(Path file) {
        this.file = file;
    }

    /** 指到文件就开一张表(不存在/读不了 = 空)。 */
    public static WatchWhitelist atFile(Path file) {
        WatchWhitelist w = new WatchWhitelist(file);
        w.reload();
        return w;
    }

    /** 这段动作文本批准过吗:先看文件变没变,再查表。 */
    public synchronized boolean allows(String id) {
        maybeReload();
        return find(id) != null;
    }

    /** 在册的批准记录(不可变快照)。 */
    public synchronized List<Entry> entries() {
        maybeReload();
        return allowed;
    }

    /**
     * 批一条(唯一的写入口;主人的命令面/面板调它,v0 的载体是配置文件本身)。已经在册就不重复,返回 false。
     */
    public synchronized boolean grant(String id, String note) {
        maybeReload();
        if (find(id) != null) {
            return false;
        }
        List<Entry> next = new ArrayList<>(allowed);
        next.add(new Entry(id, note == null ? "" : note));
        allowed = List.copyOf(next);
        save();
        return true;
    }

    /** 撤一条;不在册返回 false。 */
    public synchronized boolean revoke(String id) {
        maybeReload();
        Entry found = find(id);
        if (found == null) {
            return false;
        }
        List<Entry> next = new ArrayList<>();
        for (Entry e : allowed) {
            if (!e.id().equals(id)) {
                next.add(e);
            }
        }
        allowed = List.copyOf(next);
        save();
        return true;
    }

    private Entry find(String id) {
        for (Entry e : allowed) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** 文件变过(或从没有变成有)就重读;主人改文件即时生效,不用重启。 */
    private void maybeReload() {
        if (!Files.exists(file)) {
            if (stamp != 0) {
                allowed = List.of();
                stamp = 0;
            }
            return;
        }
        long m;
        try {
            m = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            return;   // mtime 都读不了:按现有内存表走,不动
        }
        if (m != stamp) {
            reload();
        }
    }

    private void reload() {
        List<Entry> read = new ArrayList<>();
        if (Files.exists(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.isBlank()) {
                    JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                    if (root.has("allowed") && root.get("allowed").isJsonArray()) {
                        for (var el : root.getAsJsonArray("allowed")) {
                            try {
                                JsonObject o = el.getAsJsonObject();
                                String id = o.get("id").getAsString().strip().toLowerCase(Locale.ROOT);
                                if (!id.matches("[0-9a-f]{64}")) {
                                    throw new IllegalArgumentException("id is not a 64-hex sha256");
                                }
                                String note = o.has("note") ? o.get("note").getAsString() : "";
                                read.add(new Entry(id, note));
                            } catch (RuntimeException badRow) {
                                LOG.warn("[numen-watch] 白名单有一行读不懂,跳过:{}", badRow.getMessage());
                            }
                        }
                    } else {
                        LOG.warn("[numen-watch] 白名单文件没有 allowed 数组,按空表走:{}", file.getFileName());
                    }
                }
                stamp = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException | RuntimeException ex) {
                LOG.warn("[numen-watch] 白名单读不了 {} — 按空表走(只停车):{}", file.getFileName(), ex.getMessage());
                read = new ArrayList<>();
                stamp = 0;
            }
        } else {
            stamp = 0;
        }
        allowed = List.copyOf(read);
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();
        for (Entry e : allowed) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("note", e.note());
            rows.add(o);
        }
        root.add("allowed", rows);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            LOG.warn("[numen-watch] 白名单写不了 {}: {}", file.getFileName(), ex.getMessage());
        }
    }

    /**
     * 动作文本的身份证:SHA-256(去掉首尾空白后的 UTF-8 字节)的十六进制小写。
     * 按文本本身批,不按名字批 —— 改一个字符就是另一个哈希,借不来。
     */
    public static String idOf(String action) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(action.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JVM", impossible);
        }
    }
}
