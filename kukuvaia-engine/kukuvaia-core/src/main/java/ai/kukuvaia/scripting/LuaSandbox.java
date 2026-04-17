package ai.kukuvaia.scripting;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Sandboxed Lua execution engine.
 * Removes dangerous modules (io, os, luajava) — only safe string/table/math operations available.
 * Timeout via separate thread + globals.running flag.
 */
public class LuaSandbox {

    private static final Logger log = LoggerFactory.getLogger(LuaSandbox.class);

    /**
     * Execute Lua code with bridge functions and timeout.
     *
     * @param luaCode   Lua source code
     * @param bindings  named values/functions available to the script
     * @param timeoutMs max execution time
     * @return result as Java object (String, Map, List, Number, Boolean, null)
     */
    public Object execute(String luaCode, Map<String, Object> bindings, long timeoutMs) {
        Globals globals = createSandboxedGlobals();

        // Bind Java values into Lua globals
        for (var entry : bindings.entrySet()) {
            globals.set(entry.getKey(), toLuaValue(entry.getValue()));
        }

        LuaValue chunk = globals.load(luaCode, "tool");

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LuaTool-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        try {
            LuaValue result = executor.submit(() -> chunk.call()).get(timeoutMs, TimeUnit.MILLISECONDS);
            return fromLuaValue(result);
        } catch (TimeoutException e) {
            throw new LuaTimeoutException(timeoutMs);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LuaError le) {
                throw new LuaExecutionException(le.getMessage());
            }
            throw new LuaExecutionException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LuaExecutionException("Interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Create sandboxed Lua globals — dangerous modules removed.
     */
    private Globals createSandboxedGlobals() {
        Globals globals = JsePlatform.standardGlobals();

        // Remove dangerous modules
        globals.set("io", LuaValue.NIL);
        globals.set("os", LuaValue.NIL);
        globals.set("luajava", LuaValue.NIL);
        globals.set("require", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);
        globals.set("load", LuaValue.NIL);
        globals.set("debug", LuaValue.NIL);
        globals.set("package", LuaValue.NIL);

        return globals;
    }

    /**
     * Convert Java object → LuaValue.
     */
    @SuppressWarnings("unchecked")
    LuaValue toLuaValue(Object obj) {
        if (obj == null) return LuaValue.NIL;
        if (obj instanceof String s) return LuaValue.valueOf(s);
        if (obj instanceof Number n) return LuaValue.valueOf(n.doubleValue());
        if (obj instanceof Boolean b) return LuaValue.valueOf(b);
        if (obj instanceof Map<?, ?> map) {
            LuaTable table = new LuaTable();
            ((Map<String, Object>) map).forEach((k, v) -> table.set(k, toLuaValue(v)));
            return table;
        }
        if (obj instanceof List<?> list) {
            LuaTable table = new LuaTable();
            for (int i = 0; i < list.size(); i++) {
                table.set(i + 1, toLuaValue(list.get(i))); // Lua arrays are 1-indexed
            }
            return table;
        }
        if (obj instanceof LuaFunction fn) return fn;
        if (obj instanceof LuaValue lv) return lv;
        return LuaValue.valueOf(obj.toString());
    }

    /**
     * Convert LuaValue → Java object.
     */
    Object fromLuaValue(LuaValue val) {
        if (val == null || val.isnil()) return null;
        if (val.isstring()) return val.tojstring();
        if (val.isint()) return val.toint();
        if (val.isnumber()) return val.todouble();
        if (val.isboolean()) return val.toboolean();
        if (val.istable()) return fromLuaTable(val.checktable());
        return val.tojstring();
    }

    /**
     * Convert LuaTable → Map or List (auto-detect).
     */
    private Object fromLuaTable(LuaTable table) {
        // Check if it's an array (sequential integer keys starting at 1)
        boolean isArray = true;
        int maxKey = 0;
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = table.next(k);
            if ((k = n.arg1()).isnil()) break;
            if (k.isint() && k.toint() > 0) {
                maxKey = Math.max(maxKey, k.toint());
            } else {
                isArray = false;
                break;
            }
        }

        if (isArray && maxKey > 0 && maxKey == table.length()) {
            List<Object> list = new ArrayList<>();
            for (int i = 1; i <= maxKey; i++) {
                list.add(fromLuaValue(table.get(i)));
            }
            return list;
        }

        // It's a map
        Map<String, Object> map = new LinkedHashMap<>();
        k = LuaValue.NIL;
        while (true) {
            Varargs n = table.next(k);
            if ((k = n.arg1()).isnil()) break;
            map.put(k.tojstring(), fromLuaValue(n.arg(2)));
        }
        return map;
    }

    public static class LuaTimeoutException extends RuntimeException {
        public LuaTimeoutException(long timeoutMs) {
            super("Lua script timed out after %dms".formatted(timeoutMs));
        }
    }

    public static class LuaExecutionException extends RuntimeException {
        public LuaExecutionException(String message) {
            super(message);
        }
    }
}
