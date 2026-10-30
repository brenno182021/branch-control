package br.com.el.branchcontrol;

import tools.jackson.databind.json.JsonMapper;

final class JsonCodec {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    static String write(Object value) { return MAPPER.writeValueAsString(value); }
    static <T> T read(String json, Class<T> type) { return MAPPER.readValue(json, type); }
    private JsonCodec() {}
}
