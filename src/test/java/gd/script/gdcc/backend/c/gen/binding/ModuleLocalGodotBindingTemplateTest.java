package gd.script.gdcc.backend.c.gen.binding;

import gd.script.gdcc.backend.TemplateLoader;
import gd.script.gdcc.backend.c.gen.binding.usage.EngineConstructorUsage;
import gd.script.gdcc.lir.LirModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLocalGodotBindingTemplateTest {
    @Test
    void emptySnapshotShouldRenderDocumentedNoOpSection() throws Exception {
        var header = render(List.of(), List.of());

        assertAll(
                () -> assertTrue(header.contains("Module-local Godot wrappers used by this module.")),
                () -> assertTrue(header.contains("No module-local Godot wrappers were collected for this module."))
        );
    }

    @Test
    void singletonWrapperShouldRenderOnlySingletonLookupHelperWithoutDesignatedInitializer() throws Exception {
        var header = render(
                List.of(ModuleLocalGodotBinding.singleton("SceneTree")),
                List.of()
        );

        assertAll(
                () -> assertTrue(header.contains("static inline godot_SceneTree * godot_SceneTree_singleton(void)"), header),
                () -> assertTrue(header.contains("godot_global_get_singleton(GD_STATIC_SN(u8\"SceneTree\"))"), header),
                () -> assertTrue(header.contains("gdcc_binding_lookup_context context = { 0 };"), header),
                () -> assertTrue(header.contains("context.kind = \"module_singleton\";"), header),
                () -> assertTrue(header.contains("context.function_name = \"godot_SceneTree_singleton\";"), header),
                () -> assertTrue(header.contains("context.lookup_name = \"SceneTree\";"), header),
                () -> assertTrue(header.contains("context.owner = \"@GlobalScope\";"), header),
                () -> assertTrue(header.contains("context.type = \"SceneTree\";"), header),
                () -> assertFalse(header.contains("gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){"), header),
                () -> assertFalse(header.contains("\n                .kind = \"module_singleton\""), header),
                () -> assertFalse(header.contains("godot_classdb_get_method_bind("), header),
                () -> assertFalse(header.contains("return (godot_int)"), header)
        );
    }

    @Test
    void singletonWrapperShouldKeepLookupNameSeparateFromReturnTypeName() throws Exception {
        var header = render(
                List.of(ModuleLocalGodotBinding.singleton("GameSingleton", "Node")),
                List.of()
        );

        assertAll(
                () -> assertTrue(header.contains("static inline godot_Node * godot_GameSingleton_singleton(void)"), header),
                () -> assertTrue(header.contains("static godot_Node * gdcc_module_singleton_GameSingleton = NULL;"), header),
                () -> assertTrue(header.contains("godot_global_get_singleton(GD_STATIC_SN(u8\"GameSingleton\"))"), header),
                () -> assertTrue(header.contains("context.function_name = \"godot_GameSingleton_singleton\";"), header),
                () -> assertTrue(header.contains("context.lookup_name = \"GameSingleton\";"), header),
                () -> assertTrue(header.contains("context.owner = \"@GlobalScope\";"), header),
                () -> assertTrue(header.contains("context.type = \"Node\";"), header),
                () -> assertFalse(header.contains("godot_Node_singleton"), header),
                () -> assertFalse(header.contains("godot_GameSingleton *"), header)
        );
    }

    @Test
    void classConstantWrapperShouldRenderOnlyLiteralHelper() throws Exception {
        var header = render(
                List.of(ModuleLocalGodotBinding.classConstant("Node", "NOTIFICATION_READY", "13")),
                List.of()
        );

        assertAll(
                () -> assertTrue(header.contains("static inline godot_int godot_Node_NOTIFICATION_READY(void)"), header),
                () -> assertTrue(header.contains("return (godot_int)13;"), header),
                () -> assertFalse(header.contains("godot_global_get_singleton("), header),
                () -> assertFalse(header.contains("module_singleton"), header),
                () -> assertFalse(header.contains("godot_classdb_get_method_bind("), header)
        );
    }

    @Test
    void constructorLookupFailureShouldAvoidDesignatedInitializer() throws Exception {
        var header = render(
                List.of(),
                List.of(new EngineConstructorUsage("Node", "Node", "Node", false))
        );

        assertAll(
                () -> assertTrue(header.contains("static inline godot_Node *godot_new_Node(void)"), header),
                () -> assertTrue(header.contains("gdcc_binding_lookup_context context = { 0 };"), header),
                () -> assertTrue(header.contains("context.kind = \"engine_constructor\";"), header),
                () -> assertTrue(header.contains("context.function_name = \"godot_new_Node\";"), header),
                () -> assertTrue(header.contains("context.lookup_name = \"Node\";"), header),
                () -> assertTrue(header.contains("context.owner = \"Node\";"), header),
                () -> assertTrue(header.contains("context.type = \"Node\";"), header),
                () -> assertTrue(header.contains("gdcc_binding_lookup_fail(&context);"), header),
                () -> assertFalse(header.contains("gdcc_ref_counted_init_raw"), header),
                () -> assertFalse(header.contains("gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){"), header),
                () -> assertFalse(header.contains("\n                .kind = \"engine_constructor\""), header),
                () -> assertFalse(header.contains("\n                .function_name = \"godot_new_Node\""), header)
        );
    }

    @Test
    void refCountedConstructorShouldCallInitRefAfterConstruct() throws Exception {
        var header = render(
                List.of(),
                List.of(new EngineConstructorUsage("RefCounted", "RefCounted", "RefCounted", true))
        );

        assertAll(
                () -> assertTrue(header.contains("static inline godot_RefCounted *godot_new_RefCounted(void)"), header),
                () -> assertTrue(header.contains("object = gdcc_ref_counted_init_raw(object, false);"), header)
        );
    }

    private static String render(
            List<ModuleLocalGodotBinding> usedModuleLocalBindings,
            List<EngineConstructorUsage> usedEngineConstructors
    ) throws Exception {
        return TemplateLoader.renderFromClasspath(
                "template_451/engine_method_binds.h.ftl",
                Map.of(
                        "module", new LirModule("module_local_template_test", List.of()),
                        "headerGuardPrefix", "GDEXTENSION_MODULE_LOCAL_TEMPLATE_TEST",
                        "usedEngineMethods", List.of(),
                        "usedEngineConstructors", usedEngineConstructors,
                        "usedModuleLocalBindings", usedModuleLocalBindings
                )
        );
    }
}
