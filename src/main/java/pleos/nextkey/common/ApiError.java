package pleos.nextkey.common;

import java.util.Map;

public class ApiError extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String,Object> details;

    public ApiError(int status, String code) {
        this(status,code,Map.of());
    }

    public ApiError(int status, String code, Map<String,Object> details) {
        super(code);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String,Object> details() { return details; }
}
