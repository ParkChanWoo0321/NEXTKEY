package pleos.nextkey.common;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;

public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonMapper SORTED_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final TypeReference<Map<String,Object>> OBJECT = new TypeReference<>() {};

    private Json() {}
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    public static String writeSorted(Object value) { return SORTED_MAPPER.writeValueAsString(value); }
    public static Map<String,Object> read(String json) { return MAPPER.readValue(json, OBJECT); }
}
