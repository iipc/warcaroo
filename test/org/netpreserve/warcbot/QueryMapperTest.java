package org.netpreserve.warcbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryMapperTest {

    @Test
    void testSimpleQuery() {
        QueryMapper.parse("");
        String query = "page=1&size=100";
        JsonNode result = QueryMapper.parse(query);

        assertEquals("1", result.get("page").asText());
        assertEquals("100", result.get("size").asText());
    }

    @Test
    void testMapQuery() {
        String query = "m[a]=1&m[b]=2";
        JsonNode result = QueryMapper.parse(query);
        var m = result.get("m");
        assertEquals("1", m.get("a").asText());
        assertEquals("2", m.get("b").asText());
    }

    @Test
    void testNestedQuery() {
        String query = "sort[0][field]=date&sort[0][dir]=asc";
        JsonNode result = QueryMapper.parse(query);

        JsonNode sortNode = result.get("sort");
        assertNotNull(sortNode);
        //assertTrue(sortNode.isArray());

        JsonNode firstSort = sortNode.get(0);
        assertEquals("date", firstSort.get("field").asText());
        assertEquals("asc", firstSort.get("dir").asText());
    }

    @Test
    void testComplexQuery() {
        String query = "page=1&size=100&sort[0][field]=date&sort[0][dir]=asc&filters[0][value]=10&filters[1][value]=20";
        JsonNode result = QueryMapper.parse(query);

        assertEquals("1", result.get("page").asText());
        assertEquals("100", result.get("size").asText());

        JsonNode sortNode = result.get("sort");
        assertNotNull(sortNode);
        assertTrue(sortNode.isArray());
        assertEquals("date", sortNode.get(0).get("field").asText());
        assertEquals("asc", sortNode.get(0).get("dir").asText());

        JsonNode filtersNode = result.get("filters");
        assertNotNull(filtersNode);
        assertTrue(filtersNode.isArray());
        assertEquals("10", filtersNode.get(0).get("value").asText());
        assertEquals("20", filtersNode.get(1).get("value").asText());
    }

    @Test
    void testArrayAppending() {
        String query = "colors[]=red&colors[]=blue";
        JsonNode result = QueryMapper.parse(query);

        JsonNode colorsNode = result.get("colors");
        assertNotNull(colorsNode);
        assertTrue(colorsNode.isArray());

        ArrayNode colorsArray = (ArrayNode) colorsNode;
        assertEquals("red", colorsArray.get(0).asText());
        assertEquals("blue", colorsArray.get(1).asText());
    }

    @Test
    void testMixedQuery() {
        String query = "page=1&size=100&sort[0][field]=date&sort[0][dir]=asc&colors[]=red&colors[]=blue";
        JsonNode result = QueryMapper.parse(query);

        assertEquals("1", result.get("page").asText());
        assertEquals("100", result.get("size").asText());

        JsonNode sortNode = result.get("sort");
        assertNotNull(sortNode);
        assertTrue(sortNode.isArray());
        assertEquals("date", sortNode.get(0).get("field").asText());
        assertEquals("asc", sortNode.get(0).get("dir").asText());

        JsonNode colorsNode = result.get("colors");
        assertNotNull(colorsNode);
        assertTrue(colorsNode.isArray());
        ArrayNode colorsArray = (ArrayNode) colorsNode;
        assertEquals("red", colorsArray.get(0).asText());
        assertEquals("blue", colorsArray.get(1).asText());
    }

}