package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenActuator;
import com.dwinovo.numen.client.vision.ScreenShots;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal MCP (Model Context Protocol) server, hand-rolled as JSON-RPC 2.0
 * over the JDK's built-in HTTP server — no third-party MCP SDK, no Reactor. It
 * implements exactly the surface an external agent needs to drive companions:
 * {@code initialize}, {@code tools/list}, {@code tools/call}, {@code ping}, and
 * the {@code notifications/initialized} handshake.
 *
 * <h2>Transport</h2>
 * Binds a loopback HTTP endpoint at {@code /mcp}. Claude Desktop can't dial an
 * HTTP MCP server directly, so the user points it at this endpoint through the
 * {@code mcp-remote} stdio bridge; every JSON-RPC frame arrives here as an HTTP
 * POST and its response goes straight back in the HTTP body.
 *
 * <h2>Tool surface</h2>
 * Every engine tool (from {@link ToolRegistry}, minus the config's hidden set) —
 * the script tool, the skill tool, the todo tool, plus the tools of remote MCP servers the owner connected — is
 * advertised with an extra {@code companion} argument, and calls route to
 * {@link NumenActuator#invoke}: a program runs through the companion's own
 * dispatcher and the call returns its receipt when it ends. Management tools — {@code list_companions},
 * {@code create_companion}, {@code delete_companion}, {@code lease_status} — wrap the actuator's roster
 * methods and the lease query; {@code stop} stops everything a body is executing (a program that timed out on the
 * caller's side keeps running on the server — this is the way to end it; the actuator's
 * {@code stop} knows the ext program's id, a server-started one is caught by a no-id variant).
 * Every {@code tools/call} feeds the lease (a call in flight counts as present, a successful one renews;
 * {@code lease_status} is read-only and does not) — after {@code lease_seconds} with none, the body
 * stands by, see {@link McpMode#leaseOk()}.
 * There is no take-control handshake: 模式开启期间内置大脑一轮都不开
 * (见 {@link McpMode}), 所以外部驱动者直接派活即可。Since every call is addressed
 * to a companion and each body runs its tasks independently, an agent can drive
 * several companions in parallel.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.1.0";
    /** Roster / create / delete are fast; only tool actions use the config timeout. */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    /** get_events 长轮询：默认只停 2 秒——停靠期间外脑困在这一次调用里，别的什么都做不了，
     *  它得频繁拿回方向盘才能主动行动，而不是只会应答。急件走叫醒挂点即刻送达，与这个值无关；
     *  上限 50 秒留给"就想挂着等主人"的用法，也给桥接层（mcp-remote 等）的请求超时留余量。 */
    private static final int DEFAULT_WAIT_SECONDS = 2;
    private static final int MAX_WAIT_SECONDS = 50;
    /** 活动流里一条参数/结果摘要的截断长度——面板一行放得下即可。 */
    private static final int SUMMARY_LIMIT = 90;

    /**
     * 外接大脑读的那条身体规则:内脑读的同一句({@link NumenPrompts#ONE_BODY}),接上这一侧怎么把几件活排开——外接大脑的
     * 调用没有"同一轮",每一条当场执行,要一件做完再派下一件就自己等身体空闲。说明与接入提示词({@link McpAccessPrompt})
     * 都用这一句。
     */
    static final String ONE_BODY = NumenPrompts.ONE_BODY + " A program waits for each body task it starts, so jobs "
            + "written one after another in one program run one after another.";

    /**
     * Sent to the connecting agent in the {@code initialize} handshake (MCP's
     * {@code instructions} field) — what Numen is and how to drive it, so any
     * client gets the essentials without a separately-installed skill.
     */
    private static final String INSTRUCTIONS = """
            Numen companions are AI-controlled, player-like characters inside a live Minecraft game. \
            Through this server you take control of a companion's body and play the game as it — perceive, \
            move, mine, build, craft, fight. You ARE its brain while this server runs: the built-in AI \
            stands aside entirely, and even the owner's in-game chat reaches you (via get_events) instead \
            of it. Drive the body directly — there is no 'take control' handshake.

            Loop: (1) list_companions to see who is live — create_companion by name to summon a new one, \
            delete_companion to dismiss one for good; (2) everything else is one tool, %s: a program \
            whose functions are the companion's API — perceive with numen.status.self(), numen.scan.blocks(...), \
            numen.scan.entities(...); act with numen.move.to(...), numen.work.dig(...), numen.build.place(...), numen.fight.attack(...), \
            numen.inv.craft(...), …; numen.api.help("numen.work") lists a group's functions and numen.api.help("numen.work.dig") gives one \
            function's full help. A program returns one receipt when it ends: how it ended, then stderr \
            (what the body did and what failed, one entry per call that had something to say), what it returned \
            and stdout (what it printed). It waits for each body task it starts to finish, so a long \
            job returns when it is done, with the task's account of what it changed in its stderr entry. Every call takes a 'companion' argument (name or id), so each call targets \
            one companion; just drive it, there is no take-control step. The %s tool loads one of the \
            companion's skills — the workflow guide for one kind of task — and returns its text; a name it \
            does not know answers with the list of the skills it can use. The %s tool writes down your plan for \
            work of several phases (the whole plan each call, replacing the last one); the owner sees it as a \
            checklist. The %s tool keeps the companion's own notes (remember, recall, forget); their index \
            comes with every turn of its own brain. The screenshot tool saves what this client's screen shows \
            right now as a PNG under config/numen/shots/ and answers with the file's path, size and byte \
            count — the file, never the image itself, so your context stays small; call it when you need to \
            see rather than guess.

            Rules: survival mode — the API does only what a real player can (mine to get stone; there is no \
            give or setblock). You are blind between calls, so perceive before and after acting. %s You can \
            drive several companions in parallel. Modded blocks, items, and GUIs (Create, AE2, Mekanism) \
            work natively.

            You also carry the companion's conversation: call get_events(companion) about every 2 \
            seconds while you drive it. It waits 2 seconds by default and returns instantly the moment \
            something urgent lands, so you get the wheel back every couple of seconds and can act on \
            your own initiative instead of only reacting. The owner speaking to the companion (in-game \
            chat or voice) arrives as a <query> and stops a running program between two calls; the task \
            it was waiting for keeps running, and its end arrives as a task_finished <event> among the \
            world happenings. Reply with \
            say(companion, text): the words appear in-game as the companion's chat line, speech bubble, \
            and voice. Keep your own conversation history — the game stores none for you; between \
            get_events calls nothing is lost (events queue up). Raise wait_seconds (up to 50) only when \
            you deliberately want to park and wait for the owner to speak. The stop tool cancels \
            everything a companion is currently executing — the program it runs (including one of yours \
            that timed out on your side: your call returned, but the program itself keeps running on the \
            server until you stop it) and the body task it is mid-way through — and answers with what it \
            stopped and where. The companion only stays in your hands while you keep calling: a tools/call \
            in flight already counts as you being there, and a successful one renews a lease of \
            lease_seconds (default 90). If the lease runs out the companion stands by — its running \
            program and body task are cancelled, it stays where it is and keeps its things — and your \
            next successful call brings it back. lease_status reports the lease; it does not renew it.""".formatted(
            com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.toolName(), com.dwinovo.numen.agent.tool.SkillTool.NAME,
            com.dwinovo.numen.agent.tool.TodoTool.NAME, com.dwinovo.numen.agent.tool.MemoryTool.NAME, ONE_BODY);

    private final McpConfig config;
    private final Gson gson = new Gson();
    private HttpServer http;

    public McpServer(McpConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        // get_events 长轮询会让整条线程停靠最长 50 秒，动作类工具也会阻到任务结束——
        // 池得容下几个同伴各自的轮询加并行动作。
        http.setExecutor(Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "numen-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    // ---- HTTP layer ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "");
                return;
            }
            if (!authorized(ex)) {
                respond(ex, 401, "");
                return;
            }
            // 任何通过鉴权的请求都算"对方还在"——面板的活跃时间靠它,ping 也算数。
            McpMode.instance().touch();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (RuntimeException parseErr) {
                respondJson(ex, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
                return;
            }

            if (parsed.isJsonArray()) {   // JSON-RPC batch
                JsonArray out = new JsonArray();
                for (JsonElement el : parsed.getAsJsonArray()) {
                    JsonObject r = dispatch(el.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                if (out.isEmpty()) respond(ex, 202, "");
                else respondJson(ex, out);
            } else {
                JsonObject r = dispatch(parsed.getAsJsonObject());
                if (r == null) respond(ex, 202, "");   // notification: no response
                else respondJson(ex, r);
            }
        } catch (RuntimeException fatal) {
            Constants.LOG.warn("[numen-mcp] request failed: {}", fatal.toString());
            try { respond(ex, 500, ""); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        if (config.token().isBlank()) return true;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + config.token())) return true;
        String query = ex.getRequestURI().getQuery();
        return query != null && query.contains("token=" + config.token());
    }

    private void respondJson(HttpExchange ex, JsonElement json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ---- JSON-RPC dispatch ----

    /** @return the response object, or null for a notification (no id → no reply). */
    private JsonObject dispatch(JsonObject req) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";

        // Notifications carry no id and expect no response.
        if (id == null || id.isJsonNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> okResponse(id, initializeResult(req));
                case "ping" -> okResponse(id, new JsonObject());
                case "tools/list" -> okResponse(id, toolsListResult());
                case "tools/call" -> okResponse(id, toolsCallResult(req));
                default -> errorResponse(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, "internal error: " + ex.getMessage());
        }
    }

    private JsonObject initializeResult(JsonObject req) {
        String clientProto = PROTOCOL_VERSION;
        if (req.has("params") && req.get("params").isJsonObject()) {
            JsonObject p = req.getAsJsonObject("params");
            if (p.has("protocolVersion")) clientProto = p.get("protocolVersion").getAsString();
            // 握手报的名字就是面板上"谁接进来了"的那一行。
            McpMode.instance().handshake(clientLabel(p));
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", clientProto);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        result.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "numen-mcp");
        info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    /** {@code params.clientInfo} → "Claude Desktop 1.2.3";没报名字就叫 unknown client。 */
    private static String clientLabel(JsonObject params) {
        if (!params.has("clientInfo") || !params.get("clientInfo").isJsonObject()) return "unknown client";
        JsonObject info = params.getAsJsonObject("clientInfo");
        String name = info.has("name") ? info.get("name").getAsString() : "unknown client";
        String version = info.has("version") ? info.get("version").getAsString() : "";
        return version.isBlank() ? name : name + " " + version;
    }

    // ---- tools/list ----

    private JsonObject toolsListResult() {
        JsonArray tools = new JsonArray();

        tools.add(toolDef("list_companions",
                "List the owner's live Minecraft companions (name + id). Call this first to see who you can drive.",
                objectSchema(null, false)));
        tools.add(toolDef("create_companion",
                "Summon a new companion into the world by name (3–16 letters, digits, or underscore). It arrives "
                        + "in a moment; call list_companions to confirm, then drive it directly — no 'take control' step.",
                requiredStringSchema("name",
                        "The new companion's name (3–16 letters, digits, or underscore).")));
        tools.add(toolDef("delete_companion",
                "Permanently dismiss a companion — it drops its inventory, armor and accessories and is gone for good. "
                        + "Takes its name or id.",
                requiredStringSchema("companion",
                        "Which companion to dismiss — its name or id (see list_companions).")));
        tools.add(toolDef("stop",
                "Stop everything this companion is currently executing: the program it is running (yours or one "
                        + "started another way, e.g. by the owner in-game) and the body task it is mid-way through. "
                        + "Use it when one of your tools/call timed out on your side but the program keeps running "
                        + "there. No approval needed — your token is the owner's. Idempotent: when nothing is "
                        + "running it succeeds and says so (wasRunning=false). The stopped program's receipt tells "
                        + "where it stopped; a cancelled task winds down on the server's next tick and reports as a "
                        + "task_finished(status=stopped) event. Her own pending timers are not cancelled (a timer "
                        + "only emits an event). Right after a stop, starting a new program may be refused for a "
                        + "moment while the old one finishes its receipt — retry, do not treat it as a failure.",
                objectSchema("companion", true)));
        tools.add(toolDef("lease_status",
                "Read-only diagnostics for the external brain's lease: whether it is active, how many seconds "
                        + "are left, when it was last renewed, how many calls are in flight, and whether the "
                        + "companion is standing by. Does not renew the lease.",
                objectSchema(null, false)));
        tools.add(toolDef("get_events",
                "Long-poll the companion's inbox: returns the moment something urgent arrives (the owner "
                        + "speaking to it shows up as a <query>; world happenings as <event>s), or whatever "
                        + "accumulated once wait_seconds runs out. Events are consumed on read and nothing is "
                        + "lost between calls — call this about every 2 seconds while you drive the companion.",
                getEventsSchema()));
        tools.add(toolDef("say",
                "Speak as the companion: the text appears in-game as its chat line, speech bubble, and voice "
                        + "(if one is bound). Use it to answer the owner's <query> events and to narrate what "
                        + "you are doing. Consecutive calls queue up and play in order.",
                saySchema()));
        tools.add(toolDef("screenshot",
                "Capture what the client's screen shows right now as a PNG and answer with where it was saved "
                        + "(path, width, height, bytes — never the image itself). Scaled down to a thumbnail by "
                        + "default; files live under config/numen/shots/, the newest "
                        + ScreenShots.DEFAULT_KEEP_SHOTS + " are kept by default. Use it when "
                        + "you need to see the companion's surroundings instead of guessing from events.",
                screenshotSchema()));

        // 全量给外脑,不做渐进披露:那是我们自己请求里的省法,外脑的上下文预算归它自己管,
        // 而且 MCP 客户端(Claude Code 之类)本来就会把借来的工具再延迟一次。
        for (NumenTool tool : ToolRegistry.all()) {
            if (config.isHidden(tool.name())) continue;
            tools.add(toolDef(tool.name(), tool.description(), withCompanion(tool.parameterSchema())));
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", inputSchema);
        return t;
    }

    /** An object schema with just an optional required {@code companion} string. */
    private JsonObject objectSchema(String companionKey, boolean required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        if (companionKey != null) props.add(companionKey, companionProp());
        schema.add("properties", props);
        if (companionKey != null && required) {
            JsonArray req = new JsonArray();
            req.add(companionKey);
            schema.add("required", req);
        }
        return schema;
    }

    /** An object schema with a single required string property (key + its description). */
    private JsonObject requiredStringSchema(String key, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        props.add(key, prop);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add(key);
        schema.add("required", req);
        return schema;
    }

    /** An engine tool's own schema, with a required {@code companion} argument injected. */
    private JsonObject withCompanion(java.util.Map<String, Object> parameterSchema) {
        JsonObject schema = parameterSchema == null
                ? new JsonObject()
                : gson.toJsonTree(parameterSchema).getAsJsonObject();
        schema.addProperty("type", "object");
        JsonObject props = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        props.add("companion", companionProp());
        schema.add("properties", props);
        JsonArray required = schema.has("required") && schema.get("required").isJsonArray()
                ? schema.getAsJsonArray("required") : new JsonArray();
        boolean has = false;
        for (JsonElement e : required) if ("companion".equals(e.getAsString())) has = true;
        if (!has) required.add("companion");
        schema.add("required", required);
        return schema;
    }

    private JsonObject companionProp() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    private JsonObject getEventsSchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description", "How long to wait for something urgent before returning, in seconds "
                + "(0–" + MAX_WAIT_SECONDS + ", default " + DEFAULT_WAIT_SECONDS + "). 0 = return at once. "
                + "Leave it unset for the normal drive loop; raise it only to park and wait for the owner.");
        schema.getAsJsonObject("properties").add("wait_seconds", wait);
        return schema;
    }

    private JsonObject saySchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What the companion says out loud.");
        schema.getAsJsonObject("properties").add("text", text);
        schema.getAsJsonArray("required").add("text");
        return schema;
    }

    /** screenshot:两个可选尺寸,都受上限约束,不传就是 320×180 的缩略图;keepShots 是清理上限,由调用方(vision 配置的持有方)透传。 */
    private JsonObject screenshotSchema() {
        JsonObject schema = objectSchema(null, false);
        JsonObject props = schema.getAsJsonObject("properties");
        props.add("width", pixelProp("Width in pixels (default " + ScreenShots.DEFAULT_WIDTH
                + ", capped at " + ScreenShots.MAX_WIDTH + ")."));
        props.add("height", pixelProp("Height in pixels (default " + ScreenShots.DEFAULT_HEIGHT
                + ", capped at " + ScreenShots.MAX_HEIGHT + ")."));
        props.add("keepShots", pixelProp("How many recent shots to keep in the shots folder (default "
                + ScreenShots.DEFAULT_KEEP_SHOTS + "). Pass your vision config's keepShots here."));
        return schema;
    }

    private JsonObject pixelProp(String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("description", description);
        return p;
    }

    // ---- tools/call ----

    /** 派发一次工具调用,并把它记进活动流——面板上那条流就是这里喂的。 */
    private JsonObject toolsCallResult(JsonObject req) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        JsonObject out = dispatchToolCall(name, args);
        recordActivity(name, args, out);
        return out;
    }

    /**
     * 动作行进现场缓冲（面板聊天区里的淡色工具行）。对话面的两个工具不记：
     * say 自己就是一条 SAY 气泡，get_events 是轮询心跳，记了全是噪音。
     * 带 companion 的进它自己的道，不带的（list/create）进全局道。
     */
    private void recordActivity(String name, JsonObject args, JsonObject out) {
        if ("get_events".equals(name) || "say".equals(name)) return;
        UUID who = null;
        try {
            who = resolveCompanion(args);
        } catch (Exception ignored) {
            // 解析不出就进全局道——记录不因归档失败而丢
        }
        String argsLine = argsSummary(args);
        String head = name + (argsLine.isBlank() ? "" : "  " + argsLine);
        McpTranscript.tool(who, head + " → " + textOf(out), isError(out));
    }

    /**
     * 派发一次 {@code tools/call},顺带记租约:进这个门就算外脑在场(执行期间算在场,
     * 长程序调用挂几分钟也不会被判失联);<b>成功地出来</b>才把租约时刻推到这一刻。
     * 失败的调用不续约。{@code lease_status} 是排障用的只读查询,不参与——否则它就成了
     * 变相的心跳工具。
     */
    private JsonObject dispatchToolCall(String name, JsonObject args) {
        McpMode mode = McpMode.instance();
        boolean renewing = !"lease_status".equals(name);
        if (renewing) {
            mode.noteCallStart();
        }
        boolean ok = false;
        try {
            JsonObject out = switch (name) {
                case "list_companions" -> content(listCompanions(), false);
                case "create_companion" -> handleCreate(args);
                case "delete_companion" -> handleDelete(args);
                case "get_events" -> handleGetEvents(args);
                case "say" -> handleSay(args);
                case "screenshot" -> handleScreenshot(args);
                case "stop" -> handleStop(args);
                case "lease_status" -> handleLeaseStatus();
                default -> handleToolInvoke(name, args);
            };
            ok = !isError(out);
            return out;
        } catch (TimeoutException te) {
            return content("timed out waiting for the action to finish", true);
        } catch (Exception ex) {
            return content("call failed: " + ex.getMessage(), true);
        } finally {
            if (renewing) {
                mode.noteCallEnd(ok);
            }
        }
    }

    /** 参数压成一行 {@code companion=Alice, count=5};过长截断,面板一行放得下。 */
    private static String argsSummary(JsonObject args) {
        StringBuilder sb = new StringBuilder();
        for (var entry : args.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            JsonElement v = entry.getValue();
            String text = v.isJsonPrimitive() ? v.getAsString() : v.toString();
            sb.append(entry.getKey()).append('=').append(truncate(text, 30));
            if (sb.length() > SUMMARY_LIMIT) break;
        }
        return truncate(sb.toString(), SUMMARY_LIMIT);
    }

    private static String textOf(JsonObject result) {
        if (!result.has("content") || !result.get("content").isJsonArray()) return "";
        JsonArray arr = result.getAsJsonArray("content");
        if (arr.isEmpty() || !arr.get(0).isJsonObject()) return "";
        JsonObject first = arr.get(0).getAsJsonObject();
        return first.has("text") ? truncate(first.get("text").getAsString().replace('\n', ' '), SUMMARY_LIMIT) : "";
    }

    private static boolean isError(JsonObject result) {
        return result.has("isError") && result.get("isError").getAsBoolean();
    }

    private static String truncate(String s, int limit) {
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    private String listCompanions() throws Exception {
        List<NumenActuator.Companion> list = NumenActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (list.isEmpty()) {
            return "No companions are live in the world right now. Summon one in-game first.";
        }
        StringBuilder sb = new StringBuilder("Live companions:\n");
        for (NumenActuator.Companion c : list) {
            sb.append("- ").append(c.name()).append("  (id: ").append(c.uuid()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    private JsonObject handleCreate(JsonObject args) throws Exception {
        String name = args.has("name") && !args.get("name").isJsonNull()
                ? args.get("name").getAsString().trim() : "";
        if (name.isEmpty()) {
            return content("create_companion needs a 'name' (3–16 letters, digits, or underscore)", true);
        }
        boolean ok = NumenActuator.create(name).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!ok) {
            return content("could not summon — is the game in a world?", true);
        }
        return content("summoning '" + name + "' — it arrives in a moment; call list_companions to confirm, "
                + "then drive it directly (no acquire needed)", false);
    }

    private JsonObject handleDelete(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("no such companion — call list_companions to see valid names/ids", true);
        }
        boolean ok = NumenActuator.delete(target).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "dismissed " + target + " — it drops its inventory, armor and accessories and is gone for good"
                : "could not dismiss " + target, !ok);
    }

    /** 长轮询取件：停靠在队列的急件叫醒挂点上，急件一到立即整批返回；到点清扫，
     *  有什么给什么，没有就如实说没有。叫醒只是提前，正确性由到点清扫兜底。 */
    private JsonObject handleGetEvents(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("get_events needs a 'companion' argument (a name or id from list_companions)", true);
        }
        int wait = DEFAULT_WAIT_SECONDS;
        if (args.has("wait_seconds") && !args.get("wait_seconds").isJsonNull()) {
            wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, args.get("wait_seconds").getAsInt()));
        }
        if (wait > 0) {
            NumenActuator.EventWait parked = NumenActuator.awaitUrgent(target);
            try {
                String batch = parked.batch().get(wait, TimeUnit.SECONDS);
                if (batch != null) return content(batch, false);
            } catch (TimeoutException deadline) {
                // 到点，转清扫——叫醒只是提前，正确性由清扫兜底
            } finally {
                parked.cancel().run();
            }
        }
        String rest = NumenActuator.takeEvents(target)
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(rest != null ? rest
                : "(no new events — keep polling; the owner's words and world happenings land here)", false);
    }

    private JsonObject handleSay(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("say needs a 'companion' argument (a name or id from list_companions)", true);
        }
        String text = args.has("text") && !args.get("text").isJsonNull()
                ? args.get("text").getAsString().trim() : "";
        if (text.isEmpty()) {
            return content("say needs a non-empty 'text'", true);
        }
        boolean ok = NumenActuator.say(target, text).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "said" : "could not say — is the companion live?", !ok);
    }

    /**
     * 抓一张当前画面。抓、缩、写盘都在渲染线程上做完,这里只等结果;返回的是
     * <b>文件位置与尺寸</b>,不是图本身——几十 KB 的 base64 进了上下文,这一轮就废了。
     * 没进世界时给可读的错误,不崩。
     */
    private JsonObject handleScreenshot(JsonObject args) throws Exception {
        int width = intArg(args, "width", ScreenShots.DEFAULT_WIDTH);
        int height = intArg(args, "height", ScreenShots.DEFAULT_HEIGHT);
        // keepShots 归 vision 配置管(DSH 侧),mod 不猜——调用方给多少用多少,没给按默认。
        int keepShots = intArg(args, "keepShots", ScreenShots.DEFAULT_KEEP_SHOTS);
        ScreenShots.Shot shot;
        try {
            shot = ScreenShots.grab(width, height, keepShots).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return content("could not capture the screen: " + cause.getMessage(), true);
        }
        JsonObject saved = new JsonObject();
        saved.addProperty("path", shot.path().toAbsolutePath().toString());
        saved.addProperty("width", shot.width());
        saved.addProperty("height", shot.height());
        saved.addProperty("bytes", shot.bytes());
        return content(gson.toJson(saved), false);
    }

    /** 不是整数就当没给——截图不值得为参数格式报错。 */
    private static int intArg(JsonObject args, String key, int fallback) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    /**
     * 停掉这具身体上在跑的一切(工具口径见 tools/list 里 stop 的那段话)。不弹审批:调用方握着
     * 主人的令牌,等同于主人。外脑自己那段程序的回执落地才返回——「停在哪」在回执里,而且回执
     * 落地才算让出了服务端的程序槽,stop 后立刻重派不会被拒;等太久就如实说停已经发出、回执没赶上。
     */
    private JsonObject handleStop(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("stop needs a 'companion' argument (a name or id from list_companions)", true);
        }
        NumenActuator.StopReport report;
        try {
            report = NumenActuator.stop(new NumenActuator.StopRequest(target))
                    .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException late) {
            // 两道闸已经发出去了:停是真的在发生,只是回执没赶上这一趟——如实说,不算失败
            return content("stop was issued (the program cut-off and the body stop are on their way to the "
                    + "server), but waiting for the stopped program's receipt timed out — the receipt still "
                    + "returns to the tools/call that started the program, and the task's end arrives as a "
                    + "task_finished event", false);
        }
        return content(renderStop(report), false);
    }

    /** stop 的回执读法:停了什么、之前在不在跑、停在了哪里,一样一样写清。 */
    private static String renderStop(NumenActuator.StopReport r) {
        if (!r.wasRunning()) {
            String where = r.stoppedAt() == null || r.stoppedAt().isBlank()
                    ? "the body was already idle" : r.stoppedAt();
            return "nothing was running on this client (wasRunning: false) — " + where;
        }
        StringBuilder sb = new StringBuilder("stopped (wasRunning: true):\n");
        if (r.program() != null) {
            sb.append("- program ").append(r.program()).append(" — ").append(r.stoppedAt()).append('\n');
            if (r.receipt() != null && !r.receipt().isBlank()) {
                sb.append("  its receipt: ").append(r.receipt().replace('\n', ' ')).append('\n');
            }
        }
        if (r.task() != null) {
            sb.append("- body task \"").append(r.task()).append("\" — cancelled: it winds down on the server's ")
                    .append("next tick and reports as a task_finished(status=stopped) event\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 租约的只读查询(排障用):还剩多少秒、上次续约是哪一刻、有没有调用在飞、身体是不是在待机。
     * <b>不续约</b>——查询本身不该成为心跳。
     */
    private JsonObject handleLeaseStatus() {
        McpMode m = McpMode.instance();
        long now = System.currentTimeMillis();
        boolean enabled = m.enabled();
        boolean ok = m.leaseOk();
        long renewed = m.lastRenewedMs();
        String state = !enabled ? "off" : ok ? "active" : renewed == 0 ? "never" : "expired";
        StringBuilder sb = new StringBuilder();
        sb.append("lease: ").append(state).append('\n');
        sb.append("lease_seconds: ").append(m.config().leaseSeconds()).append('\n');
        sb.append("remaining_seconds: ").append(m.leaseRemainingSeconds()).append('\n');
        sb.append("last_renewal: ");
        if (renewed == 0) {
            sb.append("never");
        } else {
            sb.append(java.time.Instant.ofEpochMilli(renewed)).append(" (")
                    .append(Math.max(0, now - renewed) / 1000).append("s ago)");
        }
        sb.append('\n');
        sb.append("in_flight_calls: ").append(m.inFlightCalls()).append('\n');
        sb.append("standby: ").append(enabled && !ok).append('\n');
        sb.append("client: ").append(m.clientName() == null ? "(none)" : m.clientName()).append('\n');
        long last = m.lastActivityMs();
        sb.append("last_request: ").append(last == 0 ? "never"
                : java.time.Instant.ofEpochMilli(last).toString() + " (" + Math.max(0, now - last) / 1000 + "s ago)");
        return content(sb.toString(), false);
    }

    private JsonObject handleToolInvoke(String toolName, JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
        }
        JsonObject toolArgs = args.deepCopy();
        toolArgs.remove("companion");
        String result = NumenActuator.invoke(target, toolName, toolArgs.toString())
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        // the external agent reads what the built-in brain reads: the result's message, never its structured data
        return content(ToolOutcome.modelText(result), ToolOutcome.failed(result));
    }

    /** Resolve the {@code companion} argument (name or UUID) to a live companion's UUID, or null. */
    private UUID resolveCompanion(JsonObject args) throws Exception {
        if (!args.has("companion") || args.get("companion").isJsonNull()) return null;
        String raw = args.get("companion").getAsString().trim();
        if (raw.isEmpty()) return null;
        List<NumenActuator.Companion> list = NumenActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // exact id match first
        for (NumenActuator.Companion c : list) {
            if (c.uuid().toString().equalsIgnoreCase(raw)) return c.uuid();
        }
        // then exact name, then case-insensitive name
        for (NumenActuator.Companion c : list) {
            if (c.name().equals(raw)) return c.uuid();
        }
        for (NumenActuator.Companion c : list) {
            if (c.name().equalsIgnoreCase(raw)) return c.uuid();
        }
        return null;
    }

    // ---- result envelopes ----

    private JsonObject content(String text, boolean isError) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(item);
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    private JsonObject okResponse(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("result", result);
        return r;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? JsonNull() : id);
        r.add("error", err);
        return r;
    }

    private static JsonElement JsonNull() {
        return com.google.gson.JsonNull.INSTANCE;
    }
}
