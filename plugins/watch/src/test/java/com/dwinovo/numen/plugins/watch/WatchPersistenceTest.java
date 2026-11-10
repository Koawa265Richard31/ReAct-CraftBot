package com.dwinovo.numen.plugins.watch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落盘两侧的契约(拍板口径 2026-11-07):
 * <b>反射白名单</b> —— 默认空;白名单外的命中只停车、不启动任何任务,结论写进回执(Hit.action);
 * 条目按动作文本的 SHA-256 认,批准的是那一段 Lua 本身(首尾空白不算差异,内容变才是另一段);
 * 主人手编文件即时生效;重启(新实例读同一文件)不丢;坏行跳过。
 * <b>事件日志</b> —— append-only JSONL,每行八字段 {@code {seq, tick, type, dim, pos, subject, source, note}},
 * seq 单调且<b>跨重启续号</b>(重启 tick 归零,续号从盘上最大值之后接着数);坏行不占 seq;
 * 过线压缩、恰一条 {@code type=compaction} 记账行。全程纯 JVM + 临时目录,不碰世界。
 */
class WatchPersistenceTest {

    @TempDir
    Path dir;

    @AfterEach
    void cleanUp() {
        // 生产路径永不摘落盘件(dropAll 不摘——单人退存档后重进世界要照常落盘);
        // 测试互不搭车走这个显式的单测缝。
        WatchEngine.dropAll();
        WatchEngine.detachForTests();
    }

    private static final UUID HER = UUID.randomUUID();
    private static final String REFLEX = "numen.say('retreating')";

    // ---- 白名单:默认空,白名单外只停车 ----

    @Test
    void whitelistDefaultsToEmptyAndABlockedActionOnlyStops() {
        WatchWhitelist whitelist = WatchWhitelist.atFile(dir.resolve("numen_watch-whitelist.json"));
        WatchEngine.attach(WatchJournal.atFile(dir.resolve("numen_watch-events.jsonl")), whitelist);
        assertTrue(whitelist.entries().isEmpty(), "白名单初始为空");
        assertFalse(whitelist.allows(WatchWhitelist.idOf(REFLEX)), "没批准过的动作查不到");

        armGuard();
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, snap(1));
        assertEquals(1, fired.size(), "命中照常发生(先停车)");
        String note = fired.get(0).action();
        assertTrue(note.contains("whitelist not approved"), note);
        assertTrue(note.contains("stopped only, nothing started"), note);
        assertTrue(note.contains(WatchWhitelist.idOf(REFLEX)), "回执里带动作的 id,主人照它批");
        // 白名单外的起步不发生:起步前的 allows 查的是同一张空表,必然 return —— 这里的断言是可见的那一半:
        // 结论写进回执,且命中本身没有被吞。
    }

    @Test
    void aWhitelistedActionIsAnnouncedAsStarting() {
        WatchWhitelist whitelist = WatchWhitelist.atFile(dir.resolve("numen_watch-whitelist.json"));
        WatchEngine.attach(WatchJournal.atFile(dir.resolve("numen_watch-events.jsonl")), whitelist);
        assertTrue(whitelist.grant(WatchWhitelist.idOf(REFLEX), "主人批的:后撤"), "首次批准成功");

        armGuard();
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, snap(1));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0).action().contains("on the owner's whitelist"), fired.get(0).action());
        assertTrue(fired.get(0).action().contains("starting it"), fired.get(0).action());
    }

    @Test
    void theWhitelistSurvivesARestartAndMatchesByActionText() {
        Path file = dir.resolve("numen_watch-whitelist.json");
        WatchWhitelist first = WatchWhitelist.atFile(file);
        assertTrue(first.grant(WatchWhitelist.idOf(REFLEX), "批:后撤"));
        // "重启" = 新实例读同一份文件
        WatchWhitelist second = WatchWhitelist.atFile(file);
        assertTrue(second.allows(WatchWhitelist.idOf(REFLEX)), "重启后批准还在(文件是载体)");
        // 按文本认:首尾空白不算差异,内容变才是另一段 —— 借不来
        assertEquals(WatchWhitelist.idOf(REFLEX), WatchWhitelist.idOf(REFLEX + "  "), "首尾空白归一");
        assertFalse(second.allows(WatchWhitelist.idOf("numen.say('hold the line')")), "内容变了就是另一段");
        assertTrue(second.revoke(WatchWhitelist.idOf(REFLEX)), "撤销");
        assertFalse(WatchWhitelist.atFile(file).allows(WatchWhitelist.idOf(REFLEX)), "撤销落盘");
    }

    @Test
    void theWhitelistReloadsWhenTheMasterEditsTheFile() throws Exception {
        Path file = dir.resolve("numen_watch-whitelist.json");
        WatchWhitelist whitelist = WatchWhitelist.atFile(file);
        String id = WatchWhitelist.idOf(REFLEX);
        assertFalse(whitelist.allows(id));
        // 主人手编文件(照 providers.json 的家法):写成 save() 的同一形状
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("note", "手批");
        rows.add(row);
        root.add("allowed", rows);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        assertTrue(whitelist.allows(id), "文件变了即时生效,不用重启");
    }

    @Test
    void malformedWhitelistRowsAreSkippedAndGoodRowsSurvive() throws Exception {
        Path file = dir.resolve("numen_watch-whitelist.json");
        Files.writeString(file, "{\"allowed\": [{\"id\": \"not-a-hash\"}, "
                + "{\"id\": \"" + WatchWhitelist.idOf(REFLEX) + "\", \"note\": \"ok\"}]}",
                StandardCharsets.UTF_8);
        WatchWhitelist whitelist = WatchWhitelist.atFile(file);
        assertEquals(1, whitelist.entries().size(), "坏行跳过,好行照读");
        assertTrue(whitelist.allows(WatchWhitelist.idOf(REFLEX)));
    }

    // ---- 事件日志:八字段、seq 跨重启续号、坏行、压缩 ----

    @Test
    void journalAndWhitelistSurviveServerStopInTheSameJvm() {
        // 单人退存档不结束进程(ServerLifecycle 的 javadoc),重进世界时 install 不重跑——
        // 落盘件若被关服清理摘掉,命中会静默不写 JSONL、已批动作会静默变回只停车。
        WatchWhitelist whitelist = WatchWhitelist.atFile(dir.resolve("numen_watch-whitelist.json"));
        WatchJournal journal = WatchJournal.atFile(dir.resolve("events.jsonl"));
        WatchEngine.attach(journal, whitelist);
        assertTrue(whitelist.grant(WatchWhitelist.idOf(REFLEX), "批:后撤"));
        armGuard();
        List<WatchApi.Hit> fired = WatchEngine.evaluate(HER, snap(1));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0).action().contains("starting it"), fired.get(0).action());
        assertEquals(1, readLines(dir.resolve("events.jsonl")).size());

        WatchEngine.dropAll();   // 关服/单人退存档:订阅清空,落盘件不摘

        // 重进世界(同一 JVM):订阅重新装,事件照写、白名单照认
        armGuard();
        fired = WatchEngine.evaluate(HER, snap(1));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0).action().contains("starting it"),
                "重进世界后已批动作不得静默变回只停车: " + fired.get(0).action());
        List<String> lines = readLines(dir.resolve("events.jsonl"));
        assertEquals(2, lines.size(), "重进世界后命中照常落盘");
        assertEquals(2, JsonParser.parseString(lines.get(1)).getAsJsonObject().get("seq").getAsLong(),
                "seq 跨关服续号,不回退不重发");
    }

    @Test
    void everyJournalLineCarriesTheEightFields() {
        WatchJournal journal = WatchJournal.atFile(dir.resolve("events.jsonl"));
        journal.append(100, "watch-hit", "", "", "guard", "attacked", "why");
        journal.append(104, "watch-hit", "", "", "low", "blood_below", "why");
        List<String> lines = readLines(dir.resolve("events.jsonl"));
        assertEquals(2, lines.size());
        Set<String> keys = Set.of("seq", "tick", "type", "dim", "pos", "subject", "source", "note");
        for (String line : lines) {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            assertEquals(keys, o.keySet(), "八字段,一行不少也不多: " + line);
        }
        assertEquals(1, JsonParser.parseString(lines.get(0)).getAsJsonObject().get("seq").getAsLong());
        assertEquals(2, JsonParser.parseString(lines.get(1)).getAsJsonObject().get("seq").getAsLong());
    }

    @Test
    void theSeqContinuesAcrossARestart() {
        Path file = dir.resolve("events.jsonl");
        WatchJournal before = WatchJournal.atFile(file);
        before.append(100, "watch-hit", "", "", "a", "attacked", "why");
        before.append(104, "watch-hit", "", "", "a", "attacked", "why");
        before.append(108, "watch-hit", "", "", "a", "attacked", "why");
        assertEquals(3, before.lastSeq());
        // "重启":新实例、同一份文件 —— tick 从 0 重新数,seq 不回退
        WatchJournal after = WatchJournal.atFile(file);
        assertEquals(3, after.lastSeq(), "续号从盘上最大 seq 之后接着数");
        after.append(2, "watch-hit", "", "", "a", "attacked", "重启后 tick 归零,seq 不归零");
        List<String> lines = readLines(file);
        assertEquals(4, lines.size());
        long previous = 0;
        for (String line : lines) {
            long seq = JsonParser.parseString(line).getAsJsonObject().get("seq").getAsLong();
            assertTrue(seq > previous, "seq 严格递增: " + line);
            previous = seq;
        }
        assertEquals(4, previous);
    }

    @Test
    void badJournalLinesAreSkippedAndDoNotConsumeSeq() throws Exception {
        Path file = dir.resolve("events.jsonl");
        WatchJournal journal = WatchJournal.atFile(file);
        journal.append(100, "watch-hit", "", "", "a", "attacked", "why");
        journal.append(104, "watch-hit", "", "", "a", "attacked", "why");
        Files.writeString(file, "this is not json\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        WatchJournal reopened = WatchJournal.atFile(file);
        assertEquals(2, reopened.lastSeq(), "坏行不占 seq");
        reopened.append(108, "watch-hit", "", "", "a", "attacked", "why");
        List<String> lines = readLines(file);
        assertEquals(3, JsonParser.parseString(lines.get(lines.size() - 1))
                .getAsJsonObject().get("seq").getAsLong(), "坏行之后的行接着排");
    }

    @Test
    void compactionKeepsTheNewestAndLeavesExactlyOneMarker() {
        Path file = dir.resolve("events.jsonl");
        WatchJournal journal = WatchJournal.atFile(file);
        // 201 条:第 201 条过线,触发恰一次压缩
        for (int i = 0; i < WatchJournal.COMPACT_KEEP + 1; i++) {
            journal.append(i, "watch-hit", "", "", "a", "attacked", "hit " + i);
        }
        List<String> lines = readLines(file);
        assertEquals(WatchJournal.COMPACT_KEEP, lines.size(), "压缩后整本恰 200 行(数据 199 + 记账 1)");
        int markers = 0;
        long firstKeptSeq = Long.MAX_VALUE;
        long lastKeptSeq = Long.MIN_VALUE;
        String lastKeptNote = "";
        for (String line : lines) {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (o.get("type").getAsString().equals(WatchJournal.COMPACTION_TYPE)) {
                markers++;
                assertTrue(o.get("note").getAsString().contains("dropped seq 1..2"),
                        "记账行写明被丢的 seq 区间: " + o.get("note").getAsString());
            } else {
                firstKeptSeq = Math.min(firstKeptSeq, o.get("seq").getAsLong());
                lastKeptSeq = Math.max(lastKeptSeq, o.get("seq").getAsLong());
                lastKeptNote = o.get("note").getAsString();
            }
        }
        assertEquals(1, markers, "恰一条 type=compaction 记账行");
        assertEquals(3, firstKeptSeq, "保留的是最新 199 条数据行");
        assertEquals(WatchJournal.COMPACT_KEEP + 1, lastKeptSeq, "最后一条数据是最后一条命中");
        assertEquals("hit " + WatchJournal.COMPACT_KEEP, lastKeptNote);
    }

    @Test
    void hitsLandInTheJournalWithTheConditionAsSource() {
        WatchWhitelist whitelist = WatchWhitelist.atFile(dir.resolve("numen_watch-whitelist.json"));
        WatchJournal journal = WatchJournal.atFile(dir.resolve("events.jsonl"));
        WatchEngine.attach(journal, whitelist);
        WatchEngine.arm(HER, WatchEngine.build("hurt_low", Optional.empty(),
                Optional.of(List.of("attacked", "blood_below")), Optional.empty(),
                kThreshold(10.0), "why", "", "p1", 1000, 0));
        WatchEngine.evaluate(HER, snap(1, 9));
        List<String> lines = readLines(dir.resolve("events.jsonl"));
        assertEquals(1, lines.size());
        JsonObject o = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("watch-hit", o.get("type").getAsString());
        assertEquals("hurt_low", o.get("subject").getAsString());
        assertEquals("attacked and blood_below", o.get("source").getAsString(), "条件形状进 source");
        assertEquals(1000, o.get("tick").getAsLong());
        assertEquals("", o.get("dim").getAsString());
    }

    // ---- 小工具 ----

    /** 单原语 + 反射动作的订阅(attacked,动作没批):走全形状 build。 */
    private static void armGuard() {
        WatchEngine.arm(HER, WatchEngine.build("guard", Optional.of("attacked"), Optional.empty(),
                Optional.empty(), WatchApi.Knobs.EMPTY, "why", REFLEX, "p1", 1000, 0));
    }

    private static WatchApi.Knobs kThreshold(double v) {
        return new WatchApi.Knobs(Optional.of(v), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static WatchSnapshot snap(int hurtCount) {
        return snap(hurtCount, 20);
    }

    private static WatchSnapshot snap(int hurtCount, float health) {
        return new WatchSnapshot(health, 20, false, false, false, false, 0, 100, 0, hurtCount, 1000,
                Map.of(), Double.MAX_VALUE, Map.of(), Map.of(), Map.of());
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
