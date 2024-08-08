package org.netpreserve.warcbot.util;

import java.util.regex.Pattern;

public class LogUtils {
    private static final Pattern BIG_STRING = Pattern.compile("\"([^\"]{20})[^\"]{40,}([^\"]{20})\"", Pattern.DOTALL | Pattern.MULTILINE);

    public static String ellipses(String s) {
        return BIG_STRING.matcher(s).replaceAll("\"$1...$2\"");
    }
}
