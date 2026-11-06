package com.dwinovo.numen.core.tools.perception;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScanApi} 的格子编码钉桩:RLE 编解码往返(含 {@code .*9}、{@code a*3b} 这类)、边长的默认/夹取/奇偶、
 * 层序 top→bottom(索引 n/2 是她脚那层,n/2+1 是脚下地板)、legend 空格分隔且每个字母都在行里出现过。
 * 只测纯函数,不引导 Minecraft。
 */
class ScanVolumeCodecTest {

    // ---- RLE 编解码 ----

    @Test
    void rleEncodesRunsAndOmitsTheCountForASingleOne() {
        assertEquals(".*9", ScanApi.rleEncode("........."));
        assertEquals("a*3b", ScanApi.rleEncode("aaab"));
        assertEquals("a", ScanApi.rleEncode("a"));
        assertEquals("a*2b", ScanApi.rleEncode("aab"));
        assertEquals("?*2.*2#*3ab", ScanApi.rleEncode("??..###ab"));
        assertEquals("", ScanApi.rleEncode(""));
    }

    @Test
    void rleDecodesBackToTheCells() {
        assertEquals(".........", ScanApi.rleDecode(".*9"));
        assertEquals("aaab", ScanApi.rleDecode("a*3b"));
        assertEquals("a", ScanApi.rleDecode("a"));
        assertEquals("??..###ab", ScanApi.rleDecode("?*2.*2#*3ab"));
        assertEquals("", ScanApi.rleDecode(""));
    }

    @Test
    void rleRoundTripsBothWays() {
        String[] cells = {".", "#", "???", "a", "ab", "ba", "aabbaabb", ".#.", ".........", "aaab",
                "....................", "?..##.ab?"}; // 全是合法格子字符(* 是计数标记,不出现在格子里)
        for (String row : cells) {
            assertEquals(row, ScanApi.rleDecode(ScanApi.rleEncode(row)), "encode→decode: " + row);
            assertEquals(ScanApi.rleEncode(row), ScanApi.rleEncode(ScanApi.rleDecode(ScanApi.rleEncode(row))),
                    "decode→encode is canonical: " + row);
        }
    }

    // ---- 边长:默认 9,夹在 3..17,偶数往外扩一格 ----

    @Test
    void sizeDefaultsToNineAndStaysOddWithinThreeToSeventeen() {
        assertEquals(9, ScanApi.sizeOf(Optional.empty()));
        assertEquals(9, ScanApi.sizeOf(Optional.of(9)));
        assertEquals(3, ScanApi.sizeOf(Optional.of(3)));
        assertEquals(17, ScanApi.sizeOf(Optional.of(17)));
        assertEquals(5, ScanApi.sizeOf(Optional.of(4)));
        assertEquals(17, ScanApi.sizeOf(Optional.of(16)));
        assertEquals(3, ScanApi.sizeOf(Optional.of(2)));
        assertEquals(17, ScanApi.sizeOf(Optional.of(18)));
        for (int asked = -5; asked <= 40; asked++) {
            int size = ScanApi.sizeOf(Optional.of(asked));
            assertTrue(size >= 3 && size <= 17, "asked " + asked + " -> " + size);
            assertTrue(size % 2 == 1, "asked " + asked + " -> " + size + " (must be odd)");
        }
    }

    // ---- 层序:索引 0 最上,索引越大越靠下;n/2 是脚那层,n/2+1 是脚下地板 ----

    @Test
    void layersGoTopToBottomWithHerFeetAtHalfAndTheFloorUnderIt() {
        int centerY = 64;
        int size = 9;
        int half = size / 2;
        int previous = Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            int y = ScanApi.layerY(centerY, half, i);
            assertTrue(y < previous, "layer " + i + " y=" + y + " must sit below layer " + (i - 1) + " y=" + previous);
            previous = y;
        }
        assertEquals(centerY + half, ScanApi.layerY(centerY, half, 0));
        assertEquals(centerY, ScanApi.layerY(centerY, half, half));
        assertEquals(centerY - 1, ScanApi.layerY(centerY, half, half + 1));
    }

    @Test
    void feetAndFloorIndexingHoldsForEveryAllowedSize() {
        for (int asked = 3; asked <= 17; asked++) {
            int size = ScanApi.sizeOf(Optional.of(asked));
            int half = size / 2;
            int centerY = 64;
            assertEquals(centerY, ScanApi.layerY(centerY, half, half), "size " + size + ": layer n/2 is her feet");
            assertEquals(centerY - 1, ScanApi.layerY(centerY, half, half + 1), "size " + size + ": layer n/2+1 is the floor");
        }
    }

    // ---- 字母与 legend ----

    @Test
    void lettersRunFromAToZThenFallBackToTheHash() {
        assertEquals('a', ScanApi.letterOf(0));
        assertEquals('b', ScanApi.letterOf(1));
        assertEquals('z', ScanApi.letterOf(25));
        assertEquals('#', ScanApi.letterOf(26));
    }

    @Test
    void legendListsUsedLettersSpaceSeparatedInAppearanceOrder() {
        Map<Character, String> letters = new LinkedHashMap<>();
        letters.put('a', "minecraft:stone");
        letters.put('b', "minecraft:water");
        assertEquals("a=minecraft:stone b=minecraft:water", ScanApi.legend(letters, letters.keySet()));
        assertEquals("b=minecraft:water", ScanApi.legend(letters, java.util.Set.of('b')));
        assertEquals("", ScanApi.legend(letters, java.util.Set.of()));
    }

    @Test
    void everyLegendLetterAppearsInTheRows() {
        // 与 volume() 同一条路径的缩样:先扫出字母,再编码行,再对账 legend 的每个键(条目 ch=id 的首字符)都在行里出现过
        Map<Character, String> letters = new LinkedHashMap<>();
        java.util.Set<Character> used = new java.util.LinkedHashSet<>();
        String[] layers = {"....aa##..", "??..bb...", "...aabb..."}; // 未做 RLE 的格子串(不含 * 等元字符)
        for (int i = 0; i < layers.length; i++) {
            java.util.Set<Character> perLayerUsed = new java.util.LinkedHashSet<>();
            StringBuilder row = new StringBuilder();
            for (char ch : layers[i].toCharArray()) {
                if (ch == 'a' || ch == 'b') {
                    String id = ch == 'a' ? "minecraft:stone" : "minecraft:water";
                    if (!letters.containsValue(id)) {
                        letters.put(ScanApi.letterOf(letters.size()), id);
                    }
                    perLayerUsed.add(ch);
                }
                row.append(ch);
            }
            used.addAll(perLayerUsed);
            String decoded = decodedLetters(ScanApi.rleEncode(row.toString()));
            // 该层的 legend 的每个键都在这一层的行里出现过(对键,不对 id 文本)
            for (char key : legendKeys(ScanApi.legend(letters, perLayerUsed))) {
                assertTrue(decoded.indexOf(key) >= 0,
                        "layer " + i + ": legend key " + key + " must appear in its rows");
            }
        }
        // 整份 legend:每个键都在某一层的行里出现过
        String legend = ScanApi.legend(letters, used);
        for (char key : legendKeys(legend)) {
            boolean seen = false;
            for (String layer : layers) {
                if (decodedLetters(ScanApi.rleEncode(layer)).indexOf(key) >= 0) {
                    seen = true;
                }
            }
            assertTrue(seen, "legend key " + key + " must appear in some row");
        }
        assertEquals("a=minecraft:stone b=minecraft:water", legend);
    }

    /** legend 条目的键:每条 {@code ch=id} 的首字符(不看 id 文本里的字母)。 */
    private static java.util.List<Character> legendKeys(String legend) {
        java.util.List<Character> keys = new java.util.ArrayList<>();
        for (String entry : legend.split(" ")) {
            if (entry.length() >= 2 && entry.charAt(1) == '=') {
                keys.add(entry.charAt(0));
            }
        }
        return keys;
    }

    /** 测试侧的 RLE 还原(与 ScanApi.rleDecode 独立写一遍,对得上才算往返成立)。 */
    private static String decodedLetters(String rle) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < rle.length()) {
            char ch = rle.charAt(i++);
            int run = 1;
            if (i < rle.length() && rle.charAt(i) == '*') {
                i++;
                int start = i;
                while (i < rle.length() && Character.isDigit(rle.charAt(i))) {
                    i++;
                }
                run = Integer.parseInt(rle.substring(start, i));
            }
            for (int r = 0; r < run; r++) {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
