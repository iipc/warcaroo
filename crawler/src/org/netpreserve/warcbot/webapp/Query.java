package org.netpreserve.warcbot.webapp;

import org.jdbi.v3.core.mapper.Nested;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

abstract class Query {
    private final Class<? extends Record> recordClass;

    @OpenAPI.Doc("Page number of results to return. Starting from 1.")
    public long page = 1;
    @OpenAPI.Doc("How many results to return per page.")
    public int size = 10;
    @OpenAPI.Doc("Fields to order the results by.")
    public List<Webapp.Sort> sort;
    @OpenAPI.Doc("Filter on the value of fields.")
    public List<Webapp.Filter> filter;

    Query(Class<? extends Record> recordClass) {
        this.recordClass = recordClass;
    }

    public Map<String, String> filterMap() {
        if (filter == null || filter.isEmpty()) return Map.of();
        return filter.stream().collect(Collectors.toMap(Webapp.Filter::field, Webapp.Filter::value));
    }

    public String orderBy() {
        if (sort == null) return "";
        var columns = getColumnNames(recordClass);
        return "ORDER BY " + sort.stream().map(s -> s.sql(columns)).collect(Collectors.joining(", "));
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
