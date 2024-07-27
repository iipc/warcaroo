package org.netpreserve.warcbot.webapp;

import org.jdbi.v3.core.mapper.Nested;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class Query {
    @OpenAPI.Doc("Page number of results to return. Starting from 1.")
    public long page = 1;
    @OpenAPI.Doc("How many results to return per page.")
    public int limit = 10;
    @OpenAPI.Doc("Fields to order the results by.")
    public String sort;
    @OpenAPI.Doc("Filter on the value of fields.")
    public List<Webapp.Filter> filter;

    public String orderBy(Class<? extends Record> recordClass) {
        if (sort == null || sort.isEmpty()) return "";
        var columns = getColumnNames(recordClass);

        StringBuilder builder = new StringBuilder();
        for (var field : sort.split(",")) {
            if (!builder.isEmpty()) builder.append(", ");
            if (field.startsWith("-")) {
                builder.append(columns.get(field.substring(1))).append(" DESC");
            } else {
                builder.append(columns.get(field));
            }
        }
        return "ORDER BY " + builder;
    }

    private final static Pattern CAMEL_HUMP = Pattern.compile("([a-z])([A-Z])");

    public static String camelToSnake(String string) {
        return CAMEL_HUMP.matcher(string).replaceAll("$1_$2").toLowerCase();
    }

    public static Map<String, String> getColumnNames(Class<? extends Record> recordClass) {
        var map = new HashMap<String, String>();
        for (var recordComponent : recordClass.getRecordComponents()) {
            if (recordComponent.getAnnotation(Nested.class) != null) {
                //noinspection unchecked
                map.putAll(getColumnNames((Class<? extends Record>) recordComponent.getType()));
            }
            String name = recordComponent.getName();
            map.put(name, Query.camelToSnake(name));
        }
        return map;
    }
}
