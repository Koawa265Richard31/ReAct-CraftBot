package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.loop.LoopStatus;
import com.dwinovo.numen.agent.loop.Phase;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.ClientToolContext;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.agent.tool.ScriptTool;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.DismissRequestPayload;
import com.dwinovo.numen.network.payload.StopCurrentProgramPayload;
import com.dwinovo.numen.network.payload.SummonRequestPayload;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.program.ProgramUplink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The public entry point for driving a companion's <strong>body</strong> from
 * an outside brain — an MCP server, a remote agent, anything that wants to be
 * the one making the gameplay decisions instead of the companion's built-in LLM.
 *
 * <h2>Actuator vs {@link NumenGateway}</h2>
 * Two deliberately separate doors:
 * <ul>
 *   <li>{@link NumenGateway#emit} feeds a message to the companion's
 *       <em>built-in brain</em>, which then decides what to do. The brain stays
 *       in charge (chat, QQ, stream relays use this).</li>
 *   <li>{@code NumenActuator} lets an <em>external</em> brain skip the built-in
 *       LLM entirely: it lists tools, takes control of a body, calls tools
 *       directly, and reads their results. The built-in brain steps aside.</li>
 * </ul>
 *
 * <h2>没有"取得控制权"这一步</h2>
 * 直接 {@link #invoke} 即可,不需要先申请、用完再归还。两个大脑不打架靠两道现成的闸:
 * <ul>
 *   <li><b>大脑层</b>——外接大脑驱动期间({@code McpMode.driving()})内置大脑
 *       一轮都不开,主人聊天、弹幕桥接送进来的消息都只进收件箱,由外脑经
 *       {@link #takeEvents} 取走、用 {@link #say} 回话;</li>
 *   <li><b>身体层</b>——{@code TaskDispatch} 的"一具身体一件活"闸门:身体上有活时新派
 *       的活当场被拒(附带话术),物理上不可能两件活撕扯一具身体。</li>
 * </ul>
 * 模式没开时外部驱动者依然能调工具,只是要和内置大脑抢身体闸门——这是刻意留的口子
 * (程序化调用者不该被 UI 模式绑住)。
 *
 * <h2>Parallelism is free</h2>
 * Every call is addressed to a companion UUID, and each companion runs its own
 * body tasks independently on the server. So one external brain can {@link #invoke}
 * on several companions — they execute concurrently.
 *
 * <h2>No conversation side effects</h2>
 * A headless {@link #invoke} does NOT touch the companion's conversation log or
 * its built-in brain's memory. The result is returned to the caller only; the
 * external brain owns the context. (The built-in brain, being paused, records
 * nothing.)
 *
 * <h2>Client-side API, any thread</h2>
 * Companions are driven by their owner's game client, so this must be called in
 * the owner's client process. Every method is safe to call from any thread — the
 * work is marshalled onto the client main thread and a {@link CompletableFuture}
 * carries the result back.
 */
public final class NumenActuator {

    /** Synthetic tool-call ids for headless invocations, disjoint from the LLM's ids. */
    private static final AtomicLong SEQ = new AtomicLong();
    /** Ids of programs an external brain runs, disjoint from the LLM's ids. */
    private static final String PROGRAM_PREFIX = "ext-";
    /** Ids of the other tools an external brain calls (tools of remote MCP servers), disjoint from the LLM's ids. */
    private static final String TOOL_PREFIX = "mcp-";

    /**
     * 外脑此刻在跑的那段程序:runExternal 收下了才记,回执(以任何方式)落地就摘。程序在服务端上,
     * 这台客户端看不见服务端的登记——谁在跑、编号是什么,只有这张表握着,{@link #stop} 靠它拿到
     * 「该停哪段」的编号。一只同伴至多一段在跑({@code AgentLoop#runAside} 拒第二段),表里至多一条。
     */
    private record ExtRun(String programId, CompletableFuture<String> receipt) {}

    private static final java.util.concurrent.ConcurrentHashMap<UUID, ExtRun> EXT_PROGRAMS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private NumenActuator() {}

    /** One of the owner's live companions: stable id + display name. */
    public record Companion(UUID uuid, String name) {}

    /**
     * The owner's companions currently live in the world — what an external brain
     * picks from. Names are what the owner sees; the UUID is the handle for every
     * other method here.
     */
    public static CompletableFuture<List<Companion>> companions() {
        CompletableFuture<List<Companion>> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            List<Companion> out = new ArrayList<>();
            for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
                out.add(new Companion(e.uuid(), e.name()));
            }
            f.complete(out);
        });
        return f;
    }

    /**
     * Summon a fresh companion into the world by name — exactly the owner's panel
     * "+" button (idempotent per name). The body arrives asynchronously (a skin
     * lookup runs first), so confirm it by polling {@link #companions()}. Provider
     * and persona are the game's own defaults; an external driver never uses the
     * built-in brain, so a companion with no provider is still fully drivable here.
     *
     * @return true once the request was sent; false if not currently in a world
     */
    public static CompletableFuture<Boolean> create(String name) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                f.complete(false);
                return;
            }
            String n = name == null ? "" : name.trim();
            NumenNetwork.sendToServer(new SummonRequestPayload(n, "", "", false));
            f.complete(true);
        });
        return f;
    }

    /**
     * Permanently dismiss a companion — its body drops its whole inventory, armor and
     * accessories (like death), then it's gone for good — exactly the panel's ✕. The roster updates
     * asynchronously; confirm via {@link #companions()}.
     *
     * @return true once the request was sent; false if not currently in a world
     */
    public static CompletableFuture<Boolean> delete(UUID companion) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() == null) {
                f.complete(false);
                return;
            }
            NumenNetwork.sendToServer(new DismissRequestPayload(companion));
            f.complete(true);
        });
        return f;
    }

    /**
     * Take whatever events are pending right now (the owner speaking arrives as a
     * {@code <query>}, world happenings as {@code <event>}s — same wire text the
     * built-in brain would see). This is the deadline sweep of a long poll; for
     * the parked wait use {@link #awaitUrgent}.
     *
     * @return the rendered event batch, or null when there was nothing
     */
    public static CompletableFuture<String> takeEvents(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(null);
            return f;
        }
        Minecraft.getInstance().execute(() ->
                f.complete(AgentLoopRegistry.getOrCreate(companion).takeEventsForExternal(false)));
        return f;
    }

    /**
     * A parked long-poll: {@code batch} completes with the whole event batch the
     * moment something urgent lands (immediately when one already has); {@code cancel}
     * withdraws the waiter — idempotent, safe after completion. Correctness never
     * rests on the wake: whatever a lost poke would have delivered, the caller's
     * deadline sweep ({@link #takeEvents}) picks up.
     */
    public record EventWait(CompletableFuture<String> batch, Runnable cancel) {}

    /**
     * Park until {@code companion} has something urgent. Check-then-register runs in
     * ONE client-thread hop, so no urgent can slip between "queue was empty" and
     * "waiter parked".
     */
    public static EventWait awaitUrgent(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null) {
            f.complete(null);
            return new EventWait(f, () -> { });
        }
        java.util.concurrent.atomic.AtomicReference<Runnable> hook =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable cancel = () -> Minecraft.getInstance().execute(() -> {
            Runnable h = hook.getAndSet(null);
            if (h != null) {
                AgentLoopRegistry.get(companion).ifPresent(lp -> lp.removeUrgentListener(h));
            }
        });
        Minecraft.getInstance().execute(() -> {
            var loop = AgentLoopRegistry.getOrCreate(companion);
            String now = loop.takeEventsForExternal(true);
            if (now != null) {
                f.complete(now);
                return;
            }
            Runnable h = () -> {
                String batch = loop.takeEventsForExternal(true);
                if (batch != null) {   // 别的取件人抢先了就继续挂着,等下一声
                    Runnable self = hook.getAndSet(null);
                    if (self != null) {
                        loop.removeUrgentListener(self);
                    }
                    f.complete(batch);
                }
            };
            hook.set(h);
            loop.addUrgentListener(h);
        });
        return new EventWait(f, cancel);
    }

    /**
     * Speak as the companion: speech bubble + chat line + its bound voice, the same
     * presentation the built-in brain's replies get. Conversation state stays with
     * the caller — the game records nothing for it.
     */
    public static CompletableFuture<Boolean> say(UUID companion, String text) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        if (companion == null || text == null || text.isBlank()) {
            f.complete(false);
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            AgentLoopRegistry.getOrCreate(companion).externalSay(text);
            f.complete(true);
        });
        return f;
    }

    /**
     * Run one tool for {@code companion} directly — the same tools the built-in
     * brain uses (see {@link ToolRegistry#all()} for the catalogue), invoked
     * without the LLM.
     *
     * <p>The script tool runs a program through the companion's own dispatcher
     * ({@code EntityAgentLoop#runExternal}): the same one the built-in brain uses, so
     * it waits for each body task to finish, stops between calls on an urgent event,
     * and the future completes with the program's receipt when it ends. A task's end goes
     * to the program that waits for it and into that receipt; a task left running when the
     * program stops ends as a task_finished event in get_events. Other tools
     * (the skill tool, tools of remote MCP servers) are invoked as they are.
     *
     * <p>不需要先取得控制权:内置大脑要么被「外接大脑」模式整体挂起,要么和这次调用
     * 一起受"一具身体一件活"闸门约束(身体忙时收到带话术的拒绝)。The future carries
     * the tool's result as a {@code ToolOutcome} JSON string; failures (unknown tool,
     * bad args, a thrown tool) come back as a {@code ToolOutcome.failure} JSON, never an
     * exceptional future.
     *
     * @param companion the body to act with
     * @param toolName  a registered tool name (case-tolerant, see {@link ToolRegistry#resolve})
     * @param argsJson  the tool's arguments as a JSON object string; null/blank means {@code {}}
     */
    public static CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        CompletableFuture<String> f = new CompletableFuture<>();
        if (companion == null || toolName == null || toolName.isBlank()) {
            f.complete(com.dwinovo.numen.agent.llm.ToolOutcome.failure("companion and toolName are required"));
            return f;
        }
        Minecraft.getInstance().execute(() -> {
            try {
                NumenTool tool = ToolRegistry.resolve(toolName);
                if (tool == null) {
                    f.complete(com.dwinovo.numen.agent.llm.ToolOutcome.failure("unknown tool: " + toolName));
                    return;
                }
                String args = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
                if (tool instanceof ScriptTool) {
                    ScriptTool.code(args);   // 参数写错当场回失败,不占工具口
                    LlmToolCall program = new LlmToolCall(PROGRAM_PREFIX + SEQ.incrementAndGet(), tool.name(), args);
                    if (!AgentLoopRegistry.getOrCreate(companion).runExternal(program, f::complete)) {
                        f.complete(com.dwinovo.numen.agent.llm.ToolOutcome.failure("a program is already running for this companion, or its "
                                + "built-in brain is acting; wait for it to end"));
                        return;
                    }
                    // 收下了就记进表,stop 工具才有编号可停;回执落地(成、败、异常都算)就摘。
                    ExtRun run = new ExtRun(program.id(), f);
                    EXT_PROGRAMS.put(companion, run);
                    f.whenComplete((result, err) -> EXT_PROGRAMS.remove(companion, run));
                    return;
                }
                AbstractClientPlayer body = ClientNumenLookup.resolve(companion);
                String id = TOOL_PREFIX + SEQ.incrementAndGet();
                ToolCall call = new ToolCall(id, toolName, args,
                        new ClientToolContext(body, companion),
                        f::complete);
                tool.invoke(call);
            } catch (RuntimeException ex) {
                f.complete(com.dwinovo.numen.agent.llm.ToolOutcome.failure(ex.getMessage()));
            }
        });
        return f;
    }

    // ---- stop:停掉这具身体上正在执行的一切 ----

    /** stop 工具的一次请求:停哪具身体。(API 参数必须是 record,不能是裸值。) */
    public record StopRequest(UUID companion) {}

    /**
     * stop 的回执:停了什么(程序/任务的名字或类型)、之前在不在跑、停在了哪里。
     *
     * @param wasRunning 从这台客户端看,发出停的那一刻有没有东西在跑(服务端自己起、这台客户端
     *                   看不见的那段程序不计入——但停的闸照样发过去,见 {@link #stop})
     * @param program   被停的那段程序(编号或类型);没有程序被停是 null
     * @param task      被叫停的那件身体活(服务端推来的镜像里那句人话);没有是 null
     * @param stoppedAt 程序停在了哪里(当场切断;她的回合被停时,回执去了哪也写在这句里)
     * @param receipt   被停的那段外脑程序交回的回执原文(「这一轮在哪里被切断」的完整账)。只有
     *                  外脑自己那段等得到——回执沿原路交回它那次 tools/call;别的段没有,是 null
     */
    public record StopReport(boolean wasRunning, String program, String task, String stoppedAt, String receipt) {

        /** 从这台客户端看什么都没在跑:幂等的成功回执(wasRunning=false,不是错误)。 */
        public static StopReport nothingRunning() {
            return new StopReport(false, null, null, null, null);
        }
    }

    /**
     * 停掉 {@code request.companion()} 这具身体上正在执行的一切:在跑的程序与正在做的身体活。两条线
     * <b>都发、一口气发</b>——{@code Program.cancel} 的回执照实说等着的那件身体活停没停,前提是身体叫停
     * ({@code CancelTasksPayload},与主人按停止同一条路)真的也发了,谁也不许只发一半。程序那一道分两式:
     * 外脑自己那段按 {@link #EXT_PROGRAMS} 里握着的编号切断,回执沿原路交回它那次 tools/call;别的段——
     * 主人在游戏里 {@code /numen drive} 起、重启重放起的,不经外脑的手、这台客户端也看不见——走「不认编号」
     * 的服务端变体({@code StopCurrentProgramPayload}),范围只在<b>这一具</b>身体上。
     *
     * <p>幂等:从这台客户端看什么都没在跑,照样成功,{@code wasRunning=false},不报错。此时空闸照发
     * ({@code StopCurrentProgramPayload} + 无活可撤的身体叫停):服务端自己起的那段程序这台客户端看不见,
     * 空闸在服务端空转、无害,真有一段漏网的就此收掉——所以 {@code wasRunning=false} 说的是「<b>我这边</b>
     * 没东西在跑」,不担保服务端一段也没有。
     *
     * <p>外脑自己那段等它的回执落地才交回执——「停在哪」的完整账在回执里,而且回执落地才算让出了
     * 服务端的程序槽,stop 之后立刻重派一段不会被「wait for it to end」拒绝。
     *
     * <p>不弹审批:调用方握着主人的令牌,等同于主人,这一路就是 OWNER_STOP 的语义。她自己定的表
     * ({@code TimerRegistry})不在停的范围内:表只是到点发一条事件的日程,不是正在执行的活。
     */
    public static CompletableFuture<StopReport> stop(StopRequest request) {
        CompletableFuture<StopReport> f = new CompletableFuture<>();
        if (request == null || request.companion() == null) {
            f.complete(StopReport.nothingRunning());
            return f;
        }
        UUID companion = request.companion();
        // 一切入口都切到客户端主线程。跳板经方法引用接住,与同文件现有各入口同一做法。
        java.util.function.Consumer<Runnable> onto = Minecraft.getInstance()::execute;
        onto.accept(() -> {
            try {
                stopNow(companion, f);
            } catch (RuntimeException ex) {
                f.completeExceptionally(ex);
            }
        });
        return f;
    }

    /**
     * 主线程上的一次 stop:看清在跑什么,一口气把两道闸都发出去。三条路互斥地走:
     * 外脑自己那段(编号在握)→ 按编号切断 + 叫停身体;内核自己在跑一批 → 主人停止同款 halt(OWNER_STOP),
     * 它自己两件都做;都不是 → 发「不认编号」的程序叫停,身体活在跑就同时叫停身体。
     *
     * <p>外脑那段<b>不走</b> halt(OWNER_STOP):halt 会把这次 tools/call 的回执改道进收件箱
     * ({@code ProgramUplink#afterwards}),外脑的 future 永远落不了地,外接程序的槽也跟着卡死——
     * 按编号直发切断,回执沿原路交回,槽自然松开。
     */
    private static void stopNow(UUID companion, CompletableFuture<StopReport> out) {
        ExtRun ext = EXT_PROGRAMS.get(companion);
        EntityAgentLoop loop = AgentLoopRegistry.get(companion).orElse(null);
        LoopStatus status = loop == null ? null : loop.status();
        boolean kernelBusy = status != null && status.phase() == Phase.TOOLS;
        String task = status != null && status.bodyTaskRunning() ? status.activity() : null;

        if (ext != null) {
            // 两道闸按 OWNER_STOP 的次序发:先切断程序(回执照实说身体活停没停),再叫停身体。
            ProgramUplink.CONNECTION.cutOff(companion, ext.programId(), true);
            NumenNetwork.sendToServer(new CancelTasksPayload(companion));
            String program = ext.programId().concat(" (your program)");
            String where = "cut off at once (the call in flight was abandoned)";
            // 回执沿原路交回它那次 tools/call;这里等它落地,把回执原文补进这份回执再交。
            // 完成方在主人客户端的主线程(deliver 那一条链),回调不需要再切线程。
            ext.receipt().whenComplete((result, err) -> out.complete(new StopReport(true, program, task, where,
                    err == null && result != null
                            ? com.dwinovo.numen.agent.llm.ToolOutcome.modelText(result) : null)));
            return;
        }
        if (kernelBusy) {
            // 内核自己在跑一批(她的那一轮):主人按停止同款——收工具口(在飞程序切断、身体叫停都在里面)、
            // 记切断点、清被取代的指令。她的回执作为事件进收件箱,外脑经 get_events 看得到。
            loop.abort();
            out.complete(new StopReport(true, "the companion's own in-flight turn", task,
                    "cut off at once; the turn's receipt lands in her inbox as an event", null));
            return;
        }
        // 不是外脑那段,也不是内核在跑的一批:程序那一道只有「不认编号」的服务端变体走得到——
        // 服务端自己起的那段在这台客户端上根本看不见。身体叫停按镜像里有没有活来发,stopBody 与它一致,
        // 程序的回执才不会说反。
        boolean bodyStop = task != null;
        NumenNetwork.sendToServer(new StopCurrentProgramPayload(companion, bodyStop));
        if (bodyStop) {
            NumenNetwork.sendToServer(new CancelTasksPayload(companion));
            out.complete(new StopReport(true, null, task,
                    "the task winds down on the server's next tick and reports as a task_finished(status=stopped) event",
                    null));
        } else {
            out.complete(new StopReport(false, null, null,
                    "nothing was running on this client; a program started on the server side (if any) was "
                            + "sent the same stop", null));
        }
    }
}
