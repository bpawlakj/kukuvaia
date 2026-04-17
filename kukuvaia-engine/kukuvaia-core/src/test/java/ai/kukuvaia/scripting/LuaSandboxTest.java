package ai.kukuvaia.scripting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LuaSandbox — sandboxed Lua execution")
class LuaSandboxTest {

    private LuaSandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new LuaSandbox();
    }

    @Test
    @DisplayName("simple return — returns string")
    void execute_simpleReturn_returnsString() {
        var result = sandbox.execute("return 'hello'", Map.of(), 5000);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    @DisplayName("bindings — variables accessible in Lua")
    void execute_withBindings_accessesVariables() {
        var result = sandbox.execute("return 'hi ' .. name", Map.of("name", "World"), 5000);
        assertThat(result).isEqualTo("hi World");
    }

    @Test
    @DisplayName("table return — converts to Map")
    void execute_tableReturn_convertsToMap() {
        var result = sandbox.execute("return {name='test', value=42}", Map.of(), 5000);
        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("name")).isEqualTo("test");
    }

    @Test
    @DisplayName("array return — converts to List")
    void execute_arrayReturn_convertsList() {
        var result = sandbox.execute("return {'a', 'b', 'c'}", Map.of(), 5000);
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).hasSize(3);
    }

    @Test
    @DisplayName("math operations — work")
    void execute_math_works() {
        var result = sandbox.execute("return math.floor(3.7)", Map.of(), 5000);
        assertThat(result.toString()).isEqualTo("3");
    }

    @Test
    @DisplayName("string operations — work")
    void execute_stringOps_work() {
        var result = sandbox.execute("return string.upper('hello')", Map.of(), 5000);
        assertThat(result).isEqualTo("HELLO");
    }

    @Test
    @DisplayName("error — throws LuaExecutionException")
    void execute_error_throws() {
        assertThatThrownBy(() -> sandbox.execute("error('boom')", Map.of(), 5000))
                .isInstanceOf(LuaSandbox.LuaExecutionException.class)
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("timeout — throws LuaTimeoutException")
    void execute_infiniteLoop_timesOut() {
        assertThatThrownBy(() -> sandbox.execute("while true do end", Map.of(), 500))
                .isInstanceOf(LuaSandbox.LuaTimeoutException.class);
    }

    // === Security tests ===

    @Test
    @DisplayName("security — io module removed")
    void execute_ioAccess_fails() {
        assertThatThrownBy(() -> sandbox.execute("return io.open('/etc/passwd')", Map.of(), 5000))
                .isInstanceOf(LuaSandbox.LuaExecutionException.class);
    }

    @Test
    @DisplayName("security — os module removed")
    void execute_osAccess_fails() {
        assertThatThrownBy(() -> sandbox.execute("return os.execute('whoami')", Map.of(), 5000))
                .isInstanceOf(LuaSandbox.LuaExecutionException.class);
    }

    @Test
    @DisplayName("security — require removed")
    void execute_requireAccess_fails() {
        assertThatThrownBy(() -> sandbox.execute("return require('os')", Map.of(), 5000))
                .isInstanceOf(LuaSandbox.LuaExecutionException.class);
    }

    @Test
    @DisplayName("Lua function binding — callable from Lua")
    void execute_functionBinding_callable() {
        org.luaj.vm2.lib.VarArgFunction fn = new org.luaj.vm2.lib.VarArgFunction() {
            @Override
            public org.luaj.vm2.Varargs invoke(org.luaj.vm2.Varargs args) {
                return org.luaj.vm2.LuaValue.valueOf("called:" + args.checkjstring(1));
            }
        };
        var result = sandbox.execute("return greet('test')", Map.of("greet", fn), 5000);
        assertThat(result).isEqualTo("called:test");
    }
}
