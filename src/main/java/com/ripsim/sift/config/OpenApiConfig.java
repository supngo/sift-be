package com.ripsim.sift.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description at the top of /v3/api-docs and the Swagger UI, plus a schema clean-up. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI siftOpenApi(ObjectProvider<BuildProperties> build) {
        BuildProperties props = build.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("Project Sift API")
                .version(props != null ? props.getVersion() : "dev")
                .description("""
                        Upload opaque binary payloads; a background worker finds the JSON embedded in each one and \
                        records its keys. Flow: `POST /v1/batches` (202 + `batchId`), then poll \
                        `GET /v1/batches/{batchId}` until `status` is `completed`.

                        Errors are RFC 7807 problem details with a stable `code` property; branch on `code`, not on \
                        the message."""));
    }

    /**
     * Our response models are Java records, and every record component is always serialized (null ones as
     * {@code null}). springdoc doesn't mark them required, so generated clients (e.g. openapi-typescript for the React
     * dashboard) would make every field optional. This marks all properties of our schemas required, and rewrites a
     * nullable reference ({@code {"type":"null","$ref":...}}, which reads as "null AND that object") into
     * {@code anyOf: [ref, null]}. ProblemDetail is left alone: Spring omits its empty fields.
     */
    @SuppressWarnings("unchecked")
    @Bean
    OpenApiCustomizer recordFieldsAreRequired() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
                return;
            }
            api.getComponents().getSchemas().forEach((name, schema) -> {
                if (name.equals("ProblemDetail") || schema.getProperties() == null) {
                    return;
                }
                @SuppressWarnings({"rawtypes"})
                Map<String, Schema> props = schema.getProperties(); // swagger-models exposes raw types
                schema.setRequired(new ArrayList<>(props.keySet()));
                props.replaceAll((prop, s) -> isNullableRef(s) ? nullOr(s.get$ref()) : s);
            });
        };
    }

    private static boolean isNullableRef(Schema<?> s) {
        return s.get$ref() != null && s.getTypes() != null && s.getTypes().contains("null");
    }

    private static Schema<?> nullOr(String ref) {
        return new JsonSchema().anyOf(List.of(new JsonSchema().$ref(ref), new JsonSchema().types(Set.of("null"))));
    }
}
