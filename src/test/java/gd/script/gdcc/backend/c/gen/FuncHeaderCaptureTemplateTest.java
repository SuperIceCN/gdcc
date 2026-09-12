package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.TemplateLoader;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors the `func.ftl` contract: a lambda with more than one capture still emits
/// exactly one `_capture` parameter. The capture struct itself still lists every field.
class FuncHeaderCaptureTemplateTest {
    @Test
    void lambdaWithMultipleCapturesEmitsSingleCaptureParameter() throws Exception {
        var header = renderHeader(lambdaWithCaptures("seed", "offset"));

        assertTrue(header.contains("typedef struct Hero_Capture__lambda_0"), header);
        assertTrue(header.contains("godot_int seed;"), header);
        assertTrue(header.contains("godot_int offset;"), header);
        assertEquals(1, countOccurrences(header, "_capture"), header);
        assertTrue(
                Pattern.compile("Hero__lambda_0\\([\\s\\S]*?\\*\\s*_capture[\\s\\S]*?\\);")
                        .matcher(header)
                        .find(),
                header
        );
    }

    @Test
    void lambdaWithOneCaptureStillEmitsSingleCaptureParameter() throws Exception {
        var header = renderHeader(lambdaWithCaptures("seed"));

        assertEquals(1, countOccurrences(header, "_capture"), header);
        assertTrue(header.contains("godot_int seed;"), header);
    }

    @Test
    void capturelessLambdaOmitsEmptyCaptureStructAndParameter() throws Exception {
        var header = renderHeader(lambdaWithCaptures());

        assertFalse(header.contains("typedef struct Hero_Capture__lambda_0"), header);
        assertEquals(0, countOccurrences(header, "_capture"), header);
        assertTrue(header.contains("Hero__lambda_0_call("), header);
        assertTrue(header.contains("Hero__lambda_0_free("), header);
    }

    @Test
    void nonLambdaDoesNotEmitCaptureParameter() throws Exception {
        var ordinary = new LirFunctionDef("run", "entry");
        ordinary.setReturnType(GdVoidType.VOID);
        ordinary.addParameter(new LirParameterDef("value", GdIntType.INT, null, ordinary));
        ordinary.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));

        var header = renderHeader(ordinary);

        assertTrue(header.contains("Hero_run("), header);
        assertEquals(0, countOccurrences(header, "_capture"), header);
    }

    private static String renderHeader(LirFunctionDef function) throws Exception {
        var classDef = new LirClassDef("Hero", "RefCounted");
        classDef.addFunction(function);
        var module = new LirModule("capture_template_test", List.of(classDef));
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        registry.addGdccClass(classDef);
        var helper = new CGenHelper(
                new CodegenContext(new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
                }, registry),
                List.of(classDef)
        );
        return TemplateLoader.renderFromClasspath(
                "template_451/entry.h.ftl",
                // Single-class module: module order already satisfies the base-before-derived
                // inheritance order entry.h.ftl now requires.
                Map.of(
                        "module", module,
                        "helper", helper,
                        "headerGuardPrefix", "GDEXTENSION_CAPTURE_TEMPLATE_TEST",
                        "inheritanceOrderedClassDefs", module.getClassDefs()
                )
        );
    }

    private static LirFunctionDef lambdaWithCaptures(String... names) {
        var lambda = new LirFunctionDef("_lambda_0", "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(GdVoidType.VOID);
        for (var name : names) {
            lambda.addCapture(new LirCaptureDef(name, GdIntType.INT, lambda));
        }
        lambda.addBasicBlock(new LirBasicBlock("entry", List.of(new ReturnInsn(null))));
        return lambda;
    }

    private static int countOccurrences(String text, String needle) {
        var count = 0;
        var from = 0;
        while (true) {
            var index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
