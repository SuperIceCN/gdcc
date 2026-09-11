package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CGenHelper;
import gd.script.gdcc.backend.c.gen.binding.EngineMethodSymbolKey;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendPropertyAccessResolverTest {
    @Test
    @DisplayName("resolveObjectProperty should return null for unknown object receiver")
    void resolveObjectPropertyReturnsNullForUnknownReceiver() {
        var hostClass = new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(hostClass));

        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("UnknownType"),
                "name",
                "load_property"
        );

        assertNull(lookup);
    }

    @Test
    @DisplayName("resolveObjectProperty should pick nearest shadowed property and classify owner as GDCC")
    void resolveObjectPropertyPicksNearestShadowedGdccOwner() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        parentClass.addProperty(new LirPropertyDef("value", GdStringType.STRING));

        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        childClass.addProperty(new LirPropertyDef("value", GdStringNameType.STRING_NAME));

        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(parentClass, childClass));

        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("ChildClass"),
                "value",
                "load_property"
        );

        assertNotNull(lookup);
        assertEquals("ChildClass", lookup.ownerClass().getName());
        assertEquals("StringName", lookup.property().getType().getTypeName());
        assertEquals(BackendPropertyAccessResolver.PropertyOwnerDispatchMode.GDCC, lookup.ownerDispatchMode());
    }

    @Test
    @DisplayName("resolveObjectProperty should classify ENGINE owner on GDCC->ENGINE chain")
    void resolveObjectPropertyClassifiesEngineOwner() {
        var nodeClass = new ExtensionGdClass(
                "Node", false, true, "Object", "core",
                List.of(), List.of(), List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("name", "String", true, true, "", "get_name", "set_name", null)),
                List.of()
        );
        var api = new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nodeClass), List.of(), List.of());

        var userClass = new LirClassDef("MyClass", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(api, List.of(userClass));

        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("MyClass"),
                "name",
                "load_property"
        );

        assertNotNull(lookup);
        assertEquals("Node", lookup.ownerClass().getName());
        assertEquals(BackendPropertyAccessResolver.PropertyOwnerDispatchMode.ENGINE, lookup.ownerDispatchMode());
    }

    @Test
    @DisplayName("resolveEnginePropertyRead/WriteAccessor should preserve raw ordinary accessors")
    void resolveEnginePropertyAccessorsForOrdinaryProperty() {
        var nodeClass = engineClass(
                "Node",
                "Object",
                List.of(
                        method("get_name", 101L, List.of(201L), "String", List.of()),
                        method("set_name", 102L, List.of(202L), "void", List.of(arg("value", "String")))
                ),
                List.of(property("name", "String", "get_name", "set_name", null))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(nodeClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Node"),
                "name",
                "load_property"
        );
        assertNotNull(lookup);

        var readAccessor = BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                bodyBuilder,
                lookup,
                "load_property"
        );
        var writeAccessor = BackendPropertyAccessResolver.resolveEnginePropertyWriteAccessor(
                bodyBuilder,
                lookup,
                "store_property"
        );

        assertEquals("Node", readAccessor.propertyOwnerClass().getName());
        assertEquals("Node", readAccessor.methodOwnerClass().getName());
        assertEquals("get_name", readAccessor.method().getName());
        assertEquals("String", readAccessor.propertyType().getTypeName());
        assertEquals("String", readAccessor.returnType().getTypeName());
        assertEquals(101L, readAccessor.methodBindSpec().hash());
        assertEquals(List.of(201L), readAccessor.methodBindSpec().hashCompatibility());
        assertEquals(0, readAccessor.parameters().size());
        assertNull(readAccessor.index());
        assertTrue(readAccessor.cFunctionName().contains("node_get_name"));
        var readResolved = readAccessor.toResolvedMethodCall();
        var readKey = EngineMethodSymbolKey.from(readResolved);
        assertNotNull(readKey);
        assertEquals(BackendMethodCallResolver.DispatchMode.ENGINE, readResolved.mode());
        assertEquals("Node", readResolved.ownerClassName());
        assertEquals("Node", readResolved.ownerType().getTypeName());
        assertEquals(readAccessor.cFunctionName(), readResolved.cFunctionName());
        assertEquals(readAccessor.methodBindSpec(), readResolved.engineMethodBindSpec());
        assertEquals(readAccessor.cFunctionName(), readKey.renderCallHelperName());

        assertEquals("set_name", writeAccessor.method().getName());
        assertEquals(102L, writeAccessor.methodBindSpec().hash());
        assertEquals("void", writeAccessor.returnType().getTypeName());
        assertEquals(1, writeAccessor.parameters().size());
        assertEquals("String", writeAccessor.parameters().getFirst().type().getTypeName());
        assertNull(writeAccessor.index());
        assertTrue(writeAccessor.cFunctionName().contains("node_set_name"));
        var writeResolved = writeAccessor.toResolvedMethodCall();
        var writeKey = EngineMethodSymbolKey.from(writeResolved);
        assertNotNull(writeKey);
        assertEquals(BackendMethodCallResolver.DispatchMode.ENGINE, writeResolved.mode());
        assertEquals("Node", writeResolved.ownerClassName());
        assertEquals(writeAccessor.cFunctionName(), writeResolved.cFunctionName());
        assertEquals(writeAccessor.cFunctionName(), writeKey.renderCallHelperName());
    }

    @Test
    @DisplayName("resolveEnginePropertyRead/WriteAccessor should preserve indexed accessor index 0")
    void resolveEnginePropertyAccessorsForIndexedProperty() {
        var windowClass = engineClass(
                "Window",
                "Object",
                List.of(
                        method("get_flag", 301L, List.of(401L), "bool", List.of(arg("flag", "enum::Window.Flags"))),
                        method(
                                "set_flag",
                                302L,
                                List.of(402L),
                                "void",
                                List.of(arg("flag", "enum::Window.Flags"), arg("enabled", "bool"))
                        )
                ),
                List.of(property("unresizable", "bool", "get_flag", "set_flag", 0))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(windowClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Window"),
                "unresizable",
                "load_property"
        );
        assertNotNull(lookup);

        var readAccessor = BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                bodyBuilder,
                lookup,
                "load_property"
        );
        var writeAccessor = BackendPropertyAccessResolver.resolveEnginePropertyWriteAccessor(
                bodyBuilder,
                lookup,
                "store_property"
        );

        assertEquals("get_flag", readAccessor.method().getName());
        assertEquals(0, readAccessor.index());
        assertEquals(1, readAccessor.parameters().size());
        assertEquals("int", readAccessor.parameters().getFirst().type().getTypeName());
        assertEquals(GdBoolType.BOOL.getTypeName(), readAccessor.returnType().getTypeName());
        assertTrue(readAccessor.cFunctionName().contains("window_get_flag"));
        assertTrue(readAccessor.cFunctionName().contains("get_flag"));
        var readResolved = readAccessor.toResolvedMethodCall();
        var readKey = EngineMethodSymbolKey.from(readResolved);
        assertNotNull(readKey);
        assertEquals("Window", readResolved.ownerClassName());
        assertEquals("int", readResolved.parameters().getFirst().type().getTypeName());
        assertEquals(readAccessor.cFunctionName(), readKey.renderCallHelperName());

        assertEquals("set_flag", writeAccessor.method().getName());
        assertEquals(0, writeAccessor.index());
        assertEquals(2, writeAccessor.parameters().size());
        assertEquals("int", writeAccessor.parameters().getFirst().type().getTypeName());
        assertEquals("bool", writeAccessor.parameters().getLast().type().getTypeName());
        assertTrue(writeAccessor.cFunctionName().contains("window_set_flag"));
        var writeResolved = writeAccessor.toResolvedMethodCall();
        var writeKey = EngineMethodSymbolKey.from(writeResolved);
        assertNotNull(writeKey);
        assertEquals("Window", writeResolved.ownerClassName());
        assertEquals("int", writeResolved.parameters().getFirst().type().getTypeName());
        assertEquals(writeAccessor.cFunctionName(), writeKey.renderCallHelperName());
    }

    @Test
    @DisplayName("engine property wrapper material should follow raw accessor names instead of property name")
    void enginePropertyAccessorMaterialShouldNotGuessFromPropertyName() {
        var windowClass = engineClass(
                "Window",
                "Object",
                List.of(
                        method("get_title_override", 501L, List.of(), "String", List.of()),
                        method("set_title_override", 502L, List.of(), "void", List.of(arg("title", "String")))
                ),
                List.of(property("window_title", "String", "get_title_override", "set_title_override", null))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(windowClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Window"),
                "window_title",
                "load_property"
        );
        assertNotNull(lookup);

        var readAccessor = BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                bodyBuilder,
                lookup,
                "load_property"
        );
        var writeAccessor = BackendPropertyAccessResolver.resolveEnginePropertyWriteAccessor(
                bodyBuilder,
                lookup,
                "store_property"
        );

        assertEquals("get_title_override", readAccessor.method().getName());
        assertEquals("set_title_override", writeAccessor.method().getName());
        assertTrue(readAccessor.cFunctionName().contains("window_get_title_override"));
        assertTrue(writeAccessor.cFunctionName().contains("window_set_title_override"));
        assertFalse(readAccessor.cFunctionName().contains("window_get_window_title"));
        assertFalse(writeAccessor.cFunctionName().contains("window_set_window_title"));
    }

    @Test
    @DisplayName("resolveEnginePropertyReadAccessor should fail-fast when raw getter method metadata is missing")
    void resolveEnginePropertyReadAccessorFailsWhenGetterMetadataMissing() {
        var nodeClass = engineClass(
                "Node",
                "Object",
                List.of(),
                List.of(property("name", "String", "get_name", "set_name", null))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(nodeClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Node"),
                "name",
                "load_property"
        );
        assertNotNull(lookup);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                        bodyBuilder,
                        lookup,
                        "load_property"
                )
        );

        assertTrue(ex.getMessage().contains("get_name"));
        assertTrue(ex.getMessage().contains("METHOD_MISSING"));
    }

    @Test
    @DisplayName("resolveEnginePropertyWriteAccessor should fail-fast when property has no raw setter")
    void resolveEnginePropertyWriteAccessorFailsWhenSetterMissing() {
        var nodeClass = engineClass(
                "Node",
                "Object",
                List.of(method("get_name", 101L, List.of(), "String", List.of())),
                List.of(property("name", "String", "get_name", null, null))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(nodeClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Node"),
                "name",
                "store_property"
        );
        assertNotNull(lookup);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveEnginePropertyWriteAccessor(
                        bodyBuilder,
                        lookup,
                        "store_property"
                )
        );

        assertTrue(ex.getMessage().contains("no raw setter"));
    }

    @Test
    @DisplayName("resolveEnginePropertyReadAccessor should fail-fast when method bind hash is zero")
    void resolveEnginePropertyReadAccessorFailsWhenHashIsZero() {
        var nodeClass = engineClass(
                "Node",
                "Object",
                List.of(method("get_name", 0L, List.of(), "String", List.of())),
                List.of(property("name", "String", "get_name", "set_name", null))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(nodeClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Node"),
                "name",
                "load_property"
        );
        assertNotNull(lookup);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                        bodyBuilder,
                        lookup,
                        "load_property"
                )
        );

        assertTrue(ex.getMessage().contains("missing method-bind hash"));
    }

    @Test
    @DisplayName("resolveEnginePropertyReadAccessor should fail-fast when indexed getter lacks index parameter")
    void resolveEnginePropertyReadAccessorFailsWhenIndexedShapeMismatches() {
        var windowClass = engineClass(
                "Window",
                "Object",
                List.of(method("get_flag", 301L, List.of(), "bool", List.of())),
                List.of(property("unresizable", "bool", "get_flag", "set_flag", 0))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(windowClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Window"),
                "unresizable",
                "load_property"
        );
        assertNotNull(lookup);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                        bodyBuilder,
                        lookup,
                        "load_property"
                )
        );

        assertTrue(ex.getMessage().contains("get_flag"));
        assertTrue(ex.getMessage().contains("No applicable overload"));
    }

    @Test
    @DisplayName("resolveEnginePropertyReadAccessor should fail-fast when getter return type mismatches property")
    void resolveEnginePropertyReadAccessorFailsWhenReturnTypeMismatches() {
        var windowClass = engineClass(
                "Window",
                "Object",
                List.of(method("get_flag", 301L, List.of(), "int", List.of(arg("flag", "int")))),
                List.of(property("unresizable", "bool", "get_flag", "set_flag", 0))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(windowClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Window"),
                "unresizable",
                "load_property"
        );
        assertNotNull(lookup);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveEnginePropertyReadAccessor(
                        bodyBuilder,
                        lookup,
                        "load_property"
                )
        );

        assertTrue(ex.getMessage().contains("not assignable to property type"));
    }

    @Test
    @DisplayName("resolveEnginePropertyWriteAccessor should fail-fast when indexed setter lacks index parameter")
    void resolveEnginePropertyWriteAccessorFailsWhenIndexedShapeMismatches() {
        var windowClass = engineClass(
                "Window",
                "Object",
                List.of(method("set_flag", 302L, List.of(), "void", List.of(arg("enabled", "bool")))),
                List.of(property("unresizable", "bool", "get_flag", "set_flag", 0))
        );
        var bodyBuilder = newBodyBuilder(apiWithClasses(windowClass), List.of());
        var lookup = BackendPropertyAccessResolver.resolveObjectProperty(
                bodyBuilder,
                new GdObjectType("Window"),
                "unresizable",
                "store_property"
        );
        assertNotNull(lookup);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveEnginePropertyWriteAccessor(
                        bodyBuilder,
                        lookup,
                        "store_property"
                )
        );

        assertTrue(ex.getMessage().contains("set_flag"));
        assertTrue(ex.getMessage().contains("No applicable overload"));
    }

    @Test
    @DisplayName("resolveObjectProperty should fail-fast when property is absent in whole hierarchy")
    void resolveObjectPropertyFailsWhenPropertyMissing() {
        var parentClass = new LirClassDef("ParentClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var childClass = new LirClassDef("ChildClass", "ParentClass", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(parentClass, childClass));

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveObjectProperty(
                        bodyBuilder,
                        new GdObjectType("ChildClass"),
                        "missing_prop",
                        "store_property"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("missing_prop"));
        assertTrue(ex.getMessage().contains("ChildClass"));
    }

    @Test
    @DisplayName("inheritance cycle should fail fast when the helper chain is constructed")
    void inheritanceCycleFailsFastAtHelperConstruction() {
        var classA = new LirClassDef("ClassA", "ClassB", false, false, Map.of(), List.of(), List.of(), List.of());
        var classB = new LirClassDef("ClassB", "ClassA", false, false, Map.of(), List.of(), List.of(), List.of());

        // The vtable planner mounted on the helper chain detects the GDCC inheritance cycle
        // while the body builder is constructed, earlier than the resolver's own hierarchy
        // walk, so the fail-fast now surfaces at fixture construction.
        var ex = assertThrows(
                IllegalStateException.class,
                () -> newBodyBuilder(emptyApi(), List.of(classA, classB))
        );
        assertTrue(ex.getMessage().contains("inheritance cycle"));
    }

    @Test
    @DisplayName("resolveObjectProperty should fail-fast when super metadata is missing")
    void resolveObjectPropertyFailsWhenSuperMetadataMissing() {
        var classA = new LirClassDef("ClassA", "MissingBase", false, false, Map.of(), List.of(), List.of(), List.of());
        var bodyBuilder = newBodyBuilder(emptyApi(), List.of(classA));

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> BackendPropertyAccessResolver.resolveObjectProperty(
                        bodyBuilder,
                        new GdObjectType("ClassA"),
                        "name",
                        "store_property"
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("MissingBase"));
    }

    private static ExtensionGdClass engineClass(String name,
                                                String inherits,
                                                List<ExtensionGdClass.ClassMethod> methods,
                                                List<ExtensionGdClass.PropertyInfo> properties) {
        return new ExtensionGdClass(
                name,
                false,
                true,
                inherits,
                "core",
                List.of(),
                methods,
                List.of(),
                properties,
                List.of()
        );
    }

    private static ExtensionGdClass.PropertyInfo property(String name,
                                                          String type,
                                                          String getter,
                                                          String setter,
                                                          Integer index) {
        return new ExtensionGdClass.PropertyInfo(name, type, true, setter != null, "", getter, setter, index);
    }

    private static ExtensionGdClass.ClassMethod method(String name,
                                                       long hash,
                                                       List<Long> hashCompatibility,
                                                       String returnType,
                                                       List<ExtensionFunctionArgument> arguments) {
        return new ExtensionGdClass.ClassMethod(
                name,
                false,
                false,
                false,
                false,
                hash,
                hashCompatibility,
                new ExtensionGdClass.ClassMethod.ClassMethodReturn(returnType),
                arguments
        );
    }

    private static ExtensionFunctionArgument arg(String name, String type) {
        return new ExtensionFunctionArgument(name, type, null, null);
    }

    private static ExtensionAPI apiWithClasses(ExtensionGdClass... classes) {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(classes), List.of(), List.of());
    }

    private static CBodyBuilder newBodyBuilder(ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }

        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var context = new CodegenContext(projectInfo, classRegistry);
        var helper = new CGenHelper(context, gdccClasses);

        var ownerClass = gdccClasses.isEmpty()
                ? new LirClassDef("HostClass", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of())
                : gdccClasses.getFirst();

        var func = new LirFunctionDef("test_func");
        func.setReturnType(GdVoidType.VOID);
        return new CBodyBuilder(helper, ownerClass, func);
    }

    private static ExtensionAPI emptyApi() {
        return new ExtensionAPI(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
