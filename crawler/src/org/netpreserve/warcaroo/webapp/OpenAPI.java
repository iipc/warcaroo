package org.netpreserve.warcaroo.webapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.generator.impl.module.SimpleTypeModule;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import org.netpreserve.warcaroo.util.Url;

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

        String summary() default "";
    }

    public static class Info {
        public String title = "Warcaroo API";
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Operation {
        public final String operationId;
        public final List<Parameter> parameters = new ArrayList<>();
        public final Map<String, Response> responses = new TreeMap<>();
        public final String description;
        public final String summary;

        public Operation(Method method, SchemaBuilder schemaBuilder) {
            this.operationId = method.getName();

            {
                Doc doc = method.getAnnotation(Doc.class);
                if (doc != null) {
                    this.summary = doc.summary().isEmpty() ? null : doc.summary();
                    this.description = doc.value().isEmpty() ? null : doc.value();
                } else {
                    this.description = null;
                    this.summary = null;
                }
            }

            if (method.getParameterCount() > 0) {
                var queryClass = method.getParameterTypes()[0];
                for (var field : queryClass.getFields()) {
                    var doc = field.getAnnotation(Doc.class);
                    parameters.add(new Parameter(field.getName(), "query",
                            doc == null ? null : doc.value(),
                            schemaBuilder.createSchemaReference(field.getGenericType()),
                            doc == null || doc.example().isEmpty() ? null : doc.example()));
                }
            }

            {
                Response response = new Response();
                responses.put("200", response);
                if (!method.getReturnType().equals(void.class)) {
                    ObjectNode schema = schemaBuilder.createSchemaReference(method.getGenericReturnType());
                    response.content.put("application/json", new MediaType(schema));
                }
            }

            for (var ex : method.getExceptionTypes()) {
                var docAnno = ex.getAnnotation(Doc.class);
                var errorAnno = ex.getAnnotation(Route.HttpError.class);
                if (errorAnno != null) {
                    Response response = new Response();
                    response.description = docAnno == null ? ex.getSimpleName() : docAnno.value();
                    responses.put(String.valueOf(errorAnno.value()), response);
                }
            }
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        public String description;
        public Map<String, MediaType> content = new TreeMap<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
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
