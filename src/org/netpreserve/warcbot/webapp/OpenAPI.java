package org.netpreserve.warcbot.webapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.generator.impl.module.SimpleTypeModule;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import org.netpreserve.warcbot.Url;

import java.lang.annotation.Retention;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

public class OpenAPI {
    public String openapi = "3.1.0";
    public Info info = new Info();
    public Map<String, PathItem> paths = new TreeMap<>();
    public Components components = new Components();

    public OpenAPI(Map<String, Route> routes) {
        var configBuilder = new SchemaGeneratorConfigBuilder(Webapp.JSON, SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule())
                .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
                .with(Option.FLATTENED_ENUMS)
                .with(SimpleTypeModule.forPrimitiveAndAdditionalTypes()
                        .withStringType(Url.class, "uri"));
        configBuilder.forTypesInGeneral()
                .withPropertySorter((first, second) -> 0);
        configBuilder.forFields()
                .withInstanceAttributeOverride((node, field, context) -> {
                    Doc doc = field.getAnnotation(Doc.class);
                    if (doc == null || doc.example().isEmpty()) return;
                    if (field.getType().isInstanceOf(int.class)) {
                        node.put("example", Integer.parseInt(doc.example()));
                    } else if (field.getType().isInstanceOf(long.class)) {
                        node.put("example", Long.parseLong(doc.example()));
                    } else {
                        node.put("example", doc.example());
                    }
                });
        var schemaBuilder = new SchemaGenerator(configBuilder.build()).buildMultipleSchemaDefinitions();
        routes.forEach((path, route) -> {
            paths.put(path, new PathItem(route, schemaBuilder));
        });
        components.schemas = schemaBuilder.collectDefinitions("components/schemas");
    }

    @Retention(RUNTIME)
    public @interface Doc {
        String value() default "";

        String example() default "";
    }

    public static class Info {
        public String title = "WarcBot API";
        public String version = "1.0";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PathItem {
        public Operation get;
        public Operation post;

        public PathItem(Route route, SchemaBuilder schemaBuilder) {
            Function<Method, Operation> newOperation = method -> method == null ? null : new Operation(method, schemaBuilder);
            get = newOperation.apply(route.methods.get("GET"));
            post = newOperation.apply(route.methods.get("POST"));
        }
    }

    public static class Operation {
        public final String operationId;
        public final List<Parameter> parameters = new ArrayList<>();
        public final Map<String, Response> responses = new TreeMap<>();

        public Operation(Method method, SchemaBuilder schemaBuilder) {
            this.operationId = method.getName();
            var queryClass = method.getParameterTypes()[0];
            for (var field : queryClass.getFields()) {
                var doc = field.getAnnotation(Doc.class);
                parameters.add(new Parameter(field.getName(), "query",
                        doc == null ? null : doc.value(),
                        schemaBuilder.createSchemaReference(field.getGenericType()),
                        doc == null || doc.example().isEmpty() ? null : doc.example()));
            }

            Response response = new Response();
            responses.put("200", response);
            response.content.put("application/json", new MediaType(schemaBuilder.createSchemaReference(method.getGenericReturnType())));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameter(
            String name,
            String in,
            String description,
            ObjectNode schema,
            Object example) {
    }

    public static class Response {
        public Map<String, MediaType> content = new TreeMap<>();
    }

    public static class MediaType {
        public final ObjectNode schema;

        public MediaType(ObjectNode schema) {
            this.schema = schema;
        }
    }

    private class Components {
        public ObjectNode schemas;
    }
}
