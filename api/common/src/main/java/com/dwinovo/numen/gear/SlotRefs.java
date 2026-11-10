package com.dwinovo.numen.gear;

import java.util.Locale;

/**
 * 容器地址与带侧别的背包格地址:解析与反拼(spec-m4-containers §1.2 的文法,协议 3 新增的两个家族)。
 *
 * <h2>文法(精确形状)</h2>
 * <pre>
 *   counterparty ::= ("my" | "her") ":inv:" &lt;0-35&gt;        # dir 5/6 的背包端
 *   boxref       ::= "box:" holder "::" anchor "::" inner
 *   holder       ::= "my" | "her"
 *   anchor       ::= "inv:" &lt;0-35&gt; | "gear:" gearname      # 这件容器躺在谁的哪一格上
 *   gearname     ::= &lt;GearSlot#name() 原样&gt; [ "#" &lt;同类第 n 个,从 0 起&gt; ]
 *   inner        ::= &lt;容器内格序号,从 0 起&gt;
 * </pre>
 *
 * <p>分隔符是 {@code ::}:anchor 本身就含冒号({@code gear:curios:back#0} 有两个、{@code inv:12} 有
 * 一个),先按 {@code ::} 切,任何地址恰好切成三段,解析无回溯、无向前看。{@code gear:} 锚点是前缀
 * {@code gear:} + {@link com.dwinovo.numen.api.gear.GearSlot#name() name()} <b>原样</b>(原版裸名
 * {@code head} 拼出 {@code gear:head},Curios 拼出 {@code gear:curios:back});不继承
 * {@code gear:<source>:<name>} 家族的 source 段,同名只留这一条拼法。
 *
 * <h2>深度恒为 1,文法写死</h2>锚点家族只有 {@code inv:}/{@code gear:},<b>永远不会再是
 * {@code box:}</b>——这不是实现上的碰巧,是文法的死规矩:第 2 层没有地址写法
 * ({@code ContainerBoxes.MAX_BOX_DEPTH} 与这里的家族表钉在一起,改一个不改另一个,类加载当场炸)。
 * 所以 {@code box:} 地址永远只有一层,不会出现递归形状。
 *
 * <h2>第三方没有写法(§3.3 的第一条落点)</h2>holder 只有 {@code my|her} 两个词,分别绑定到一次请求的
 * 两个当事人:服务端解析时两方从 UUID 现取。改包伪造一个 {@code box:steve::…} 在 {@link #parse} 就拒。
 *
 * <p>裸 {@code inv:<n>}(无侧别)在这里<b>不合法</b>——那是 M3 int 字段({@code fromSlot}/{@code toSlot})
 * 的事,两个家族不混写。
 */
public final class SlotRefs {

    private SlotRefs() {}

    /** §五第 1 条的原句:地址不合文法 / 持有者不是 my|her / 第三方持有 / dir 5/6 两端类型不对。 */
    public static final String PARSE_REFUSAL =
            "that box address doesn't parse — expected box:<her|my>::<inv:<0-35> | gear:<name>#<n>>::<inner slot>";

    /**
     * 解析结果:解析成功 {@code error} 为 null;失败时 {@code error} 是给面板的一句话。
     * 区分两个家族看 {@code boxInner}:≥ 0 是 boxref(inner 永远从 0 起),-1 是 counterparty;
     * boxref 的锚点看 {@code gearName}(非 null 即 gear 锚点,否则 {@code invIndex} ≥ 0 即 inv 锚点)。
     */
    public record Parsed(String error, boolean hers, int invIndex,
                         String gearName, int gearOrdinal, int boxInner) {}

    /** 只认本类文法里的新家族;空串、旧家族、一切不合文法的形状都回同一句 {@link #PARSE_REFUSAL}。 */
    public static Parsed parse(String ref) {
        String s = ref == null ? "" : ref.trim();
        if (s.contains("::")) {
            return parseBox(s);
        }
        return parseCounterparty(s);
    }

    /** boxref:「box:<holder>::<anchor>::<inner>」——恰好三段,anchor 只收 inv:/gear: 两族。 */
    private static Parsed parseBox(String s) {
        String[] parts = s.split("::", -1);
        if (parts.length != 3) {
            return bad();
        }
        if (!parts[0].equals("box:my") && !parts[0].equals("box:her")) {
            return bad();   // holder 不是 my|her:第三方的写法在文法里不存在(§1.3)
        }
        boolean hers = parts[0].equals("box:her");
        String anchor = parts[1];
        int invIndex = -1;
        String gearName = null;
        int gearOrdinal = -1;
        if (anchor.startsWith("inv:")) {
            invIndex = parseSmall(anchor.substring(4));
            if (invIndex < 0 || invIndex > 35) {
                return bad();
            }
        } else if (anchor.startsWith("gear:")) {
            String name = anchor.substring(5);
            int hash = name.lastIndexOf('#');
            if (hash >= 0) {
                String tail = name.substring(hash + 1);
                int ordinal = parseSmall(tail);
                if (ordinal >= 0) {
                    gearOrdinal = ordinal;
                    name = name.substring(0, hash);
                }
            }
            if (name.isEmpty()) {
                return bad();
            }
            gearName = name;
            gearOrdinal = Math.max(gearOrdinal, 0);   // 无「#n」后缀 = 同类第 0 个
        } else {
            return bad();   // 锚点家族只有 inv:/gear:——box: 锚点在此断掉,深度恒为 MAX_BOX_DEPTH
        }
        int inner = parseSmall(parts[2]);
        if (inner < 0) {
            return bad();
        }
        return new Parsed(null, hers, invIndex, gearName, gearOrdinal, inner);
    }

    /** counterparty:「(my|her):inv:<0-35>」——dir 5/6 的背包端。 */
    private static Parsed parseCounterparty(String s) {
        String prefix = s.startsWith("my:inv:") ? "my:inv:"
                : s.startsWith("her:inv:") ? "her:inv:" : null;
        if (prefix != null) {
            int index = parseSmall(s.substring(prefix.length()));
            if (index >= 0 && index <= 35) {
                return new Parsed(null, prefix.startsWith("her"), index, null, -1, -1);
            }
        }
        return bad();
    }

    /** 反向拼装,面板与日志用。 */
    public static String formatBox(boolean hers, String anchor, int inner) {
        return "box:" + (hers ? "her" : "my") + "::" + anchor + "::" + inner;
    }

    /** 失败一律同一句:给出文法形状,不说「哪里错」——错的形状无穷多,文法只有一条。 */
    private static Parsed bad() {
        return new Parsed(PARSE_REFUSAL, false, -1, null, -1, -1);
    }

    /** 非负小整数:纯数字、至多 9 位(防溢出),否则 -1。 */
    private static int parseSmall(String digits) {
        if (digits.isEmpty() || digits.length() > 9) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(digits);
    }

    /** 给日志的一句话:锚点段的原样(gear 锚点带上序号后缀)。 */
    public static String anchorText(Parsed boxRef) {
        if (boxRef.gearName() != null) {
            return "gear:" + boxRef.gearName()
                    + (boxRef.gearOrdinal() > 0 ? "#" + boxRef.gearOrdinal() : "");
        }
        return "inv:" + boxRef.invIndex();
    }

    /** 防御:本类只产小写词表;大小写不同的地址一律不认(面板照本类文法拼,不会发大写)。 */
    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
