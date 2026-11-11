package com.stonewu.agenteam.service.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 参数只替换一次，名称里的引号、占位符和正则符号都作为原文保留。 */
final class MessageTemplate {
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)\\}");
    private final Pattern pattern;
    private final List<Integer> indexes;

    MessageTemplate(String template) {
        var builder = new StringBuilder("^");
        var selected = new ArrayList<Integer>();
        var matcher = ARGUMENT.matcher(template);
        int start = 0;
        while (matcher.find()) {
            builder.append(Pattern.quote(template.substring(start, matcher.start()))).append("([\\s\\S]*?)");
            selected.add(Integer.parseInt(matcher.group(1)));
            start = matcher.end();
        }
        builder.append(Pattern.quote(template.substring(start))).append("$");
        pattern = Pattern.compile(builder.toString());
        indexes = List.copyOf(selected);
    }

    Object[] match(String value) {
        if (indexes.isEmpty()) {
            return null;
        }
        var matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        var arguments = new Object[indexes.stream().mapToInt(Integer::intValue).max().orElse(0) + 1];
        for (int index = 0; index < indexes.size(); index++) {
            int position = indexes.get(index);
            String argument = matcher.group(index + 1);
            if (arguments[position] != null && !arguments[position].equals(argument)) {
                return null;
            }
            arguments[position] = argument;
        }
        return arguments;
    }

    static String format(String template, Object... arguments) {
        if (arguments.length == 0) {
            return template;
        }
        var matcher = ARGUMENT.matcher(template);
        var result = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(index < arguments.length
                ? String.valueOf(arguments[index]) : matcher.group()));
        }
        return matcher.appendTail(result).toString();
    }
}
