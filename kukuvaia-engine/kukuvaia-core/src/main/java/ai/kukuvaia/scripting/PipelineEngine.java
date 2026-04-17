package ai.kukuvaia.scripting;

import ai.kukuvaia.extensions.ToolBridgeAPI;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes YAML pipeline steps with variable interpolation and Lua escape hatch.
 *
 * Step types:
 * - fs.read, fs.write, fs.edit, fs.find, fs.search, fs.exists — filesystem
 * - db.query, db.execute — database
 * - http.get, http.post — HTTP
 * - lua: — inline Lua code
 * - return: — final result
 */
public class PipelineEngine {

    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final long LUA_TIMEOUT_MS = 10_000;

    private final ToolBridgeAPI bridge;
    private final LuaSandbox luaSandbox;
    private final Map<String, Object> variables = new LinkedHashMap<>();

    public PipelineEngine(ToolBridgeAPI bridge) {
        this.bridge = bridge;
        this.luaSandbox = new LuaSandbox();
    }

    /**
     * Execute a list of pipeline steps.
     *
     * @param steps parsed YAML steps (List of Maps)
     * @param args  input arguments from LLM tool call
     * @return final result as string (JSON or plain text)
     */
    @SuppressWarnings("unchecked")
    public String execute(List<Map<String, Object>> steps, Map<String, Object> args) {
        // Seed variables with input args
        variables.putAll(args);
        Object lastResult = null;

        for (Map<String, Object> step : steps) {
            String resultVar = (String) step.get("as");

            if (step.containsKey("fs.read")) {
                lastResult = execFsRead((Map<String, Object>) step.get("fs.read"));
            } else if (step.containsKey("fs.write")) {
                lastResult = execFsWrite((Map<String, Object>) step.get("fs.write"));
            } else if (step.containsKey("fs.edit")) {
                lastResult = execFsEdit((Map<String, Object>) step.get("fs.edit"));
            } else if (step.containsKey("fs.find")) {
                lastResult = execFsFind((Map<String, Object>) step.get("fs.find"));
            } else if (step.containsKey("fs.search")) {
                lastResult = execFsSearch((Map<String, Object>) step.get("fs.search"));
            } else if (step.containsKey("fs.exists")) {
                lastResult = execFsExists((Map<String, Object>) step.get("fs.exists"));
            } else if (step.containsKey("db.query")) {
                lastResult = execDbQuery((Map<String, Object>) step.get("db.query"));
            } else if (step.containsKey("db.execute")) {
                lastResult = execDbExecute((Map<String, Object>) step.get("db.execute"));
            } else if (step.containsKey("http.get")) {
                lastResult = execHttpGet((Map<String, Object>) step.get("http.get"));
            } else if (step.containsKey("http.post")) {
                lastResult = execHttpPost((Map<String, Object>) step.get("http.post"));
            } else if (step.containsKey("lua")) {
                lastResult = execLua(step.get("lua").toString());
            } else if (step.containsKey("return")) {
                return formatReturn(step.get("return"));
            }

            if (resultVar != null && lastResult != null) {
                variables.put(resultVar, lastResult);
            }
        }

        // If no explicit return, return last result
        return lastResult != null ? toJsonString(lastResult) : "";
    }

    // === Filesystem steps ===

    private Object execFsRead(Map<String, Object> args) {
        return bridge.readFile(interpolate(str(args, "path")));
    }

    private Object execFsWrite(Map<String, Object> args) {
        return bridge.writeFile(interpolate(str(args, "path")), interpolate(str(args, "content")));
    }

    private Object execFsEdit(Map<String, Object> args) {
        return bridge.editFile(interpolate(str(args, "path")),
                interpolate(str(args, "old_text")), interpolate(str(args, "new_text")));
    }

    private Object execFsFind(Map<String, Object> args) {
        return bridge.findFiles(interpolate(str(args, "glob")));
    }

    private Object execFsSearch(Map<String, Object> args) {
        return bridge.searchFiles(interpolate(str(args, "query")),
                args.containsKey("glob") ? interpolate(str(args, "glob")) : null);
    }

    private Object execFsExists(Map<String, Object> args) {
        return bridge.fileExists(interpolate(str(args, "path")));
    }

    // === Database steps ===

    private Object execDbQuery(Map<String, Object> args) {
        String params = args.containsKey("params") ? toJsonString(args.get("params")) : "[]";
        return bridge.dbQuery(interpolate(str(args, "sql")), interpolate(params));
    }

    private Object execDbExecute(Map<String, Object> args) {
        String params = args.containsKey("params") ? toJsonString(args.get("params")) : "[]";
        return bridge.dbExecute(interpolate(str(args, "sql")), interpolate(params));
    }

    // === HTTP steps ===

    private Object execHttpGet(Map<String, Object> args) {
        return bridge.httpGet(interpolate(str(args, "url")));
    }

    private Object execHttpPost(Map<String, Object> args) {
        return bridge.httpPost(interpolate(str(args, "url")),
                interpolate(str(args, "body")));
    }

    // === Lua execution ===

    private Object execLua(String luaCode) {
        // Build bindings: all pipeline variables + bridge functions
        Map<String, Object> bindings = new LinkedHashMap<>(variables);

        // Expose bridge as Lua functions
        bindings.put("fs", createFsBridge());
        bindings.put("db", createDbBridge());
        bindings.put("http", createHttpBridge());
        bindings.put("log", (LuaFunction) new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                bridge.log(args.checkjstring(1));
                return LuaValue.NIL;
            }
        });

        return luaSandbox.execute(luaCode, bindings, LUA_TIMEOUT_MS);
    }

    private LuaValue createFsBridge() {
        var table = new org.luaj.vm2.LuaTable();
        table.set("read", new OneArgFunc(path -> bridge.readFile(path)));
        table.set("write", new TwoArgFunc((path, content) -> bridge.writeFile(path, content)));
        table.set("edit", new ThreeArgFunc((path, old, nw) -> bridge.editFile(path, old, nw)));
        table.set("find", new OneArgFunc(glob -> bridge.findFiles(glob)));
        table.set("search", new TwoArgFunc((query, glob) -> bridge.searchFiles(query, glob)));
        table.set("exists", new OneArgFunc(path -> String.valueOf(bridge.fileExists(path))));
        return table;
    }

    private LuaValue createDbBridge() {
        var table = new org.luaj.vm2.LuaTable();
        table.set("query", new TwoArgFunc((sql, params) -> bridge.dbQuery(sql, params)));
        table.set("execute", new TwoArgFunc((sql, params) -> bridge.dbExecute(sql, params)));
        return table;
    }

    private LuaValue createHttpBridge() {
        var table = new org.luaj.vm2.LuaTable();
        table.set("get", new OneArgFunc(url -> bridge.httpGet(url)));
        table.set("get_with_header", new ThreeArgFunc((url, name, value) ->
                bridge.httpGetWithHeader(url, name, value)));
        table.set("post", new TwoArgFunc((url, body) -> bridge.httpPost(url, body)));
        return table;
    }

    // === Variable interpolation ===

    String interpolate(String template) {
        if (template == null) return null;
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            Object value = variables.get(varName);
            m.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value.toString()) : "");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String formatReturn(Object returnSpec) {
        if (returnSpec instanceof String s) return interpolate(s);
        return toJsonString(returnSpec);
    }

    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private String toJsonString(Object obj) {
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    // === Helper Lua function wrappers ===

    private static class OneArgFunc extends VarArgFunction {
        private final java.util.function.Function<String, String> fn;
        OneArgFunc(java.util.function.Function<String, String> fn) { this.fn = fn; }
        @Override public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(fn.apply(args.checkjstring(1)));
        }
    }

    private static class TwoArgFunc extends VarArgFunction {
        private final java.util.function.BiFunction<String, String, String> fn;
        TwoArgFunc(java.util.function.BiFunction<String, String, String> fn) { this.fn = fn; }
        @Override public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(fn.apply(args.checkjstring(1),
                    args.narg() > 1 ? args.checkjstring(2) : ""));
        }
    }

    private static class ThreeArgFunc extends VarArgFunction {
        interface TriFunction { String apply(String a, String b, String c); }
        private final TriFunction fn;
        ThreeArgFunc(TriFunction fn) { this.fn = fn; }
        @Override public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(fn.apply(
                    args.checkjstring(1), args.checkjstring(2), args.checkjstring(3)));
        }
    }
}
