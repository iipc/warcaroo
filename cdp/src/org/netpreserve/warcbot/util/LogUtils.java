package org.netpreserve.warcaroo.util;

import java.util.regex.Pattern;

public class LogUtils {
    public static String ellipses(String string) {
        return ellipses(string, 40);
    }

    public static String ellipses(String string, int maxLength) {
        boolean inQuotes = false;
        var output = new StringBuilder();
        var quote = new StringBuilder();
        for (int i = 0; i < string.length(); i++) {
            if (string.charAt(i) == '\"') {
                if (inQuotes) {
                    output.append('"');
                    if (quote.length() < maxLength) {
                        output.append(quote);
                    } else {
                        output.append(quote, 0, maxLength / 2);
                        output.append("...");
                        output.append(quote, quote.length() - maxLength / 2, quote.length());
                    }
                    output.append('"');
                    quote.setLength(0);
                }
                inQuotes = !inQuotes;
            } else if (inQuotes) {
                quote.append(string.charAt(i));
            } else {
                output.append(string.charAt(i));
            }
        }
        return output.toString();
    }
}
