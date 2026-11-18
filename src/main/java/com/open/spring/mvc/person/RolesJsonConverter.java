package com.open.spring.mvc.person;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a person's roles as one JSON array of role names, for example ["ROLE_USER","ROLE_STUDENT"], in the
 * roles_json column of the person table. Persisted as TEXT so it works on SQLite and MySQL alike.
 *
 * The model keeps the names and nothing else: which role means what is decided where access is granted, in the
 * API and the security rules, not in this table.
 */
@Converter(autoApply = false)
public class RolesJsonConverter implements AttributeConverter<List<String>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? new ArrayList<String>() : attribute);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert roles to JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        try {
            if (dbData == null || dbData.isBlank()) return new ArrayList<>();
            return MAPPER.readValue(dbData, new TypeReference<ArrayList<String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse roles JSON", e);
        }
    }
}
