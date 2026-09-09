package gd.script.gdcc.lir.insn;

import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.GdInstruction.OperandKind;
import gd.script.gdcc.enums.GdInstruction.ReturnKind;
import gd.script.gdcc.exception.LirInsnParsingException;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirInstruction.StringOperand;
import gd.script.gdcc.lir.LirInstruction.VariableOperand;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnParser;
import gd.script.gdcc.lir.parser.SimpleLirBlockInsnSerializer;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Anchors the frozen LIR contract for `call_super_method`
/// (`gdcc_low_ir.md` §Call Instructions; vtable plan Step 5).
///
/// Covers opcode shape, `(method_name, object, args...)` operands, serialize/parse round-trip,
/// and negative operand-count / operand-kind cases.
class CallSuperMethodInsnContractTest {

    private static List<LirInstruction> parse(String input) {
        return new SimpleLirBlockInsnParser().parse(new StringReader(input));
    }

    private static String serialize(LirInstruction insn) {
        var sw = new StringWriter();
        try {
            new SimpleLirBlockInsnSerializer().serialize(List.of(insn), sw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return sw.toString();
    }

    @Test
    void opcodeIsCallSuperMethod() {
        var insn = new CallSuperMethodInsn(null, "greet", "self", List.of());
        assertEquals(GdInstruction.CALL_SUPER_METHOD, insn.opcode());
        assertEquals("call_super_method", insn.opcode().opcode());
    }

    @Test
    void implementsCallInstruction() {
        assertInstanceOf(CallInstruction.class, new CallSuperMethodInsn(null, "greet", "self", List.of()));
    }

    @Test
    void operandKindsMatchEnumDeclaration() {
        var declared = GdInstruction.CALL_SUPER_METHOD.operandKinds();
        assertEquals(List.of(OperandKind.STRING, OperandKind.VARIABLE, OperandKind.VARARGS), declared);
        assertEquals(2, GdInstruction.CALL_SUPER_METHOD.minOperands());
        assertEquals(ReturnKind.OPTIONAL, GdInstruction.CALL_SUPER_METHOD.returnKind());
    }

    @Test
    void operandsAreMethodNameThenObjectThenArgs() {
        var insn = new CallSuperMethodInsn("res", "greet", "self", List.of(new VariableOperand("a")));
        assertEquals("greet", ((StringOperand) insn.operands().getFirst()).value());
        assertEquals("self", ((VariableOperand) insn.operands().get(1)).id());
        assertEquals("a", ((VariableOperand) insn.operands().get(2)).id());
    }

    @Test
    void serializesQuotedMethodNameAndObject() {
        var insn = new CallSuperMethodInsn("res", "greet", "self", List.of(new VariableOperand("a")));
        assertEquals("$res = call_super_method \"greet\" $self $a;\n", serialize(insn));
    }

    @Test
    void parsesQuotedMethodNameObjectAndArgs() {
        var insns = parse("$res = call_super_method \"greet\" $self $a;\n");
        assertEquals(1, insns.size());
        var insn = assertInstanceOf(CallSuperMethodInsn.class, insns.getFirst());
        assertEquals("res", insn.resultId());
        assertEquals("greet", insn.methodName());
        assertEquals("self", insn.objectId());
        assertEquals(List.of(new VariableOperand("a")), insn.args());
    }

    @Test
    void roundTripPreservesOperands() {
        var original = new CallSuperMethodInsn("result", "greet", "self", List.of(new VariableOperand("arg")));
        var parsed = assertInstanceOf(
                CallSuperMethodInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed), () -> "round-trip failed: " + serialize(original));
    }

    @Test
    void roundTripPreservesMissingResultAndEmptyArgs() {
        var original = new CallSuperMethodInsn(null, "greet", "self", List.of());
        var parsed = assertInstanceOf(
                CallSuperMethodInsn.class,
                parse(serialize(original)).getFirst()
        );
        assertTrue(original.checkEquals(parsed), () -> "round-trip failed: " + serialize(original));
    }

    @Test
    void parseRejectsMissingObjectOperand() {
        assertThrows(
                LirInsnParsingException.class,
                () -> parse("call_super_method \"greet\";\n")
        );
    }

    @Test
    void parseRejectsVariableMethodName() {
        assertThrows(
                LirInsnParsingException.class,
                () -> parse("call_super_method $method $self;\n")
        );
    }

    @Test
    void parseRejectsStringObject() {
        assertThrows(
                LirInsnParsingException.class,
                () -> parse("call_super_method \"greet\" \"self\";\n")
        );
    }
}
