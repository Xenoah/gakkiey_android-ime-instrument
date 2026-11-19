package com.xenoah.gakkikeys;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Romaji -> hiragana composer (no kanji dictionary). Pure Java. */
public final class RomajiComposer {
    private static final Map<String, String> T = new HashMap<>();
    private static final Set<String> PREFIX = new HashSet<>();

    private static void row(String c, String a, String i, String u, String e, String o) {
        String[] k = {a, i, u, e, o};
        String v = "aiueo";
        for (int n = 0; n < 5; n++) if (k[n] != null) T.put(c + v.charAt(n), k[n]);
    }

    private static void yoon(String c, String base) {
        row(c, base + "ゃ", base + "ぃ", base + "ゅ", base + "ぇ", base + "ょ");
    }

    static {
        row("", "あ", "い", "う", "え", "お");
        row("k", "か", "き", "く", "け", "こ");
        row("g", "が", "ぎ", "ぐ", "げ", "ご");
        row("s", "さ", "し", "す", "せ", "そ");
        row("z", "ざ", "じ", "ず", "ぜ", "ぞ");
        row("t", "た", "ち", "つ", "て", "と");
        row("d", "だ", "ぢ", "づ", "で", "ど");
        row("n", "な", "に", "ぬ", "ね", "の");
        row("h", "は", "ひ", "ふ", "へ", "ほ");
        row("b", "ば", "び", "ぶ", "べ", "ぼ");
        row("p", "ぱ", "ぴ", "ぷ", "ぺ", "ぽ");
        row("m", "ま", "み", "む", "め", "も");
        row("y", "や", "い", "ゆ", "いぇ", "よ");
        row("r", "ら", "り", "る", "れ", "ろ");
        row("l", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ");
        row("x", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ");
        row("w", "わ", "うぃ", "う", "うぇ", "を");
        row("f", "ふぁ", "ふぃ", "ふ", "ふぇ", "ふぉ");
        row("v", "ゔぁ", "ゔぃ", "ゔ", "ゔぇ", "ゔぉ");
        row("q", "くぁ", "くぃ", "く", "くぇ", "くぉ");
        row("j", "じゃ", "じ", "じゅ", "じぇ", "じょ");
        row("ch", "ちゃ", "ち", "ちゅ", "ちぇ", "ちょ");
        row("sh", "しゃ", "し", "しゅ", "しぇ", "しょ");
        row("ts", "つぁ", "つぃ", "つ", "つぇ", "つぉ");
        row("th", "てゃ", "てぃ", "てゅ", "てぇ", "てょ");
        row("dh", "でゃ", "でぃ", "でゅ", "でぇ", "でょ");
        row("cy", "ちゃ", "ちぃ", "ちゅ", "ちぇ", "ちょ");
        yoon("ky", "き"); yoon("gy", "ぎ"); yoon("sy", "し"); yoon("zy", "じ");
        yoon("jy", "じ"); yoon("ty", "ち"); yoon("dy", "ぢ"); yoon("ny", "に");
        yoon("hy", "ひ"); yoon("by", "び"); yoon("py", "ぴ"); yoon("my", "み");
        yoon("ry", "り"); yoon("fy", "ふ"); yoon("vy", "ゔ");
        row("xy", "ゃ", "ぃ", "ゅ", "ぇ", "ょ");
        row("ly", "ゃ", "ぃ", "ゅ", "ぇ", "ょ");
        T.put("ca", "か"); T.put("cu", "く"); T.put("co", "こ");
        T.put("n'", "ん"); T.put("xn", "ん");
        T.put("xtu", "っ"); T.put("ltu", "っ"); T.put("xtsu", "っ"); T.put("ltsu", "っ");
        T.put("xwa", "ゎ"); T.put("lwa", "ゎ");
        T.put("-", "ー");
        for (String k : T.keySet())
            for (int i = 1; i < k.length(); i++) PREFIX.add(k.substring(0, i));
    }

    private final StringBuilder kana = new StringBuilder();
    private String pending = "";
    private boolean katakana;

    public boolean isEmpty() {
        return kana.length() == 0 && pending.isEmpty();
    }

    public void clear() {
        kana.setLength(0);
        pending = "";
        katakana = false;
    }

    public void type(char c) {
        pending += Character.toLowerCase(c);
        resolve();
    }

    private static boolean consonant(char c) {
        return c >= 'a' && c <= 'z' && "aiueon".indexOf(c) < 0;
    }

    private void resolve() {
        while (!pending.isEmpty()) {
            if (pending.startsWith("nn")) { // Google-IME style: nn+vowel -> ん+な行
                if (pending.length() == 2) break;
                char c3 = pending.charAt(2);
                kana.append('ん');
                pending = ("aiueoy".indexOf(c3) >= 0) ? pending.substring(1) : pending.substring(2);
                continue;
            }
            String hit = T.get(pending);
            if (hit != null) { kana.append(hit); pending = ""; break; }
            if (PREFIX.contains(pending)) break;
            char a = pending.charAt(0);
            char b = pending.length() > 1 ? pending.charAt(1) : 0;
            if (b != 0 && consonant(a) && (a == b || (a == 't' && b == 'c'))) {
                kana.append('っ');
                pending = pending.substring(1);
                continue;
            }
            if (a == 'n' && b != 0) {
                kana.append('ん');
                pending = pending.substring(1);
                continue;
            }
            kana.append(a);
            pending = pending.substring(1);
        }
    }

    public void backspace() {
        if (!pending.isEmpty()) pending = pending.substring(0, pending.length() - 1);
        else if (kana.length() > 0) kana.setLength(kana.length() - 1);
        if (isEmpty()) katakana = false;
    }

    public void toggleKatakana() {
        katakana = !katakana;
    }

    public String display() {
        String s = kana + pending;
        return katakana ? toKatakana(s) : s;
    }

    /** Finalize (trailing n -> ん) and clear. */
    public String finish() {
        if (pending.equals("n") || pending.equals("nn")) { kana.append('ん'); pending = ""; }
        String s = display();
        clear();
        return s;
    }

    static String toKatakana(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3041 && c <= 0x3096) c += 0x60;
            b.append(c);
        }
        return b.toString();
    }
}
