package de.raindancer118.shelved.book;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final String SEP = "\u001F";

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(SEP, list);
    }

    @Override
    public List<String> convertToEntityAttribute(String s) {
        return s == null || s.isEmpty() ? List.of() : Arrays.asList(s.split(SEP));
    }
}
