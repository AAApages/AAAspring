package com.open.spring.mvc.person;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Keeps the JSON a person is sent and read as the same as it was when roles were rows of their own: each role is
 * {"name": "ROLE_USER"}. The pages read role.name. Reading also accepts a bare string, which is how they are stored.
 */
public final class RoleNameJson {
    private RoleNameJson() {}

    public static class Writer extends StdSerializer<String> {
        public Writer() {
            super(String.class);
        }

        @Override
        public void serialize(String roleName, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("name", roleName);
            generator.writeEndObject();
        }
    }

    public static class Reader extends StdDeserializer<String> {
        public Reader() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            return node.isTextual() ? node.asText() : node.path("name").asText(null);
        }
    }
}
