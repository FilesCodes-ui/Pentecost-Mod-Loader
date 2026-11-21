package khi.pentecost.remap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextRemapper {
    private static final Pattern CLASS = Pattern.compile("(?:[A-Za-z_]\\w*[/.])*[\\w$]*?class_\\d+(?:\\$\\w+)*");
    private static final Pattern MEMBER = Pattern.compile("\\b(?:method_\\d+|field_\\d+|comp_\\d+|m_\\d+_|f_\\d+_)\\b");
    private static final String DESCRIPTOR_PREFIX = "BCDFIJSZLV";

    private final Mappings.NameTable t;

    public TextRemapper(Mappings.NameTable t) {
        this.t = t;
    }

    public String remap(String s) {
        if (s == null || s.length() < 5) {
            return s;
        }
        boolean cls = s.contains("class_");
        boolean mem = s.contains("method_") || s.contains("field_") || s.contains("comp_")
                || s.contains("m_") || s.contains("f_");
        if (!cls && !mem) {
            return s;
        }
        String out = s;
        if (cls) {
            Matcher m = CLASS.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(classToken(m.group())));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        if (mem) {
            Matcher m = MEMBER.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String name = m.group();
                String mapped = t.methods().get(name);
                if (mapped == null) {
                    mapped = t.fields().getOrDefault(name, name);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(mapped));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private String classToken(String token) {
        boolean dots = token.indexOf('.') >= 0 && token.indexOf('/') < 0;
        String key = dots ? token.replace('.', '/') : token;
        for (int k = 0; k <= Math.min(16, key.length() - 1); k++) {
            if (k > 0 && DESCRIPTOR_PREFIX.indexOf(key.charAt(k - 1)) < 0) {
                break;
            }
            String rest = key.substring(k);
            String mapped = mapClass(rest);
            if (!mapped.equals(rest)) {
                String full = key.substring(0, k) + mapped;
                return dots ? full.replace('/', '.') : full;
            }
        }
        return token;
    }

    public String mapClass(String internal) {
        String key = internal;
        String tail = "";
        while (true) {
            String hit = t.classes().get(key);
            if (hit != null) {
                return hit + tail;
            }
            int cut = key.lastIndexOf('$');
            if (cut < 0) {
                return internal;
            }
            tail = key.substring(cut) + tail;
            key = key.substring(0, cut);
        }
    }
}
