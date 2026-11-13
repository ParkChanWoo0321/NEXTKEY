package pleos.nextkey.common;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String,Object>> OBJECT = new TypeReference<>() {};

    private Json() {}
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    public static Map<String,Object> read(String json) { return MAPPER.readValue(json, OBJECT); }
}
