package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.Codegen;
import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.TemplateLoader;
import gd.script.gdcc.backend.c.gen.binding.GenerateRenderFacade;
import gd.script.gdcc.backend.c.gen.binding.GodotBindingSupport;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageBuffer;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageSession;
import gd.script.gdcc.backend.c.gen.fatptr.CObjectFatPtrCollector;
import gd.script.gdcc.backend.c.gen.insn.*;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.lir.*;
import gd.script.gdcc.lir.insn.*;
import gd.script.gdcc.lir.validation.ControlFlowIntegrityValidator;
import gd.script.gdcc.lir.validation.LirPublicAbiValidator;
import gd.script.gdcc.lir.validation.LifecycleInstructionRestrictionValidator;
import gd.script.gdcc.scope.ParameterDef;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.*;
import gd.script.gdcc.util.CCodeFormatter;
import freemarker.template.TemplateException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CCodegen implements Codegen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CCodegen.class);
    private static final EnumMap<GdInstruction, CInsnGen<? extends LirInstruction>> INSN_GENS = new EnumMap<>(GdInstruction.class);

    static {
        registerInsnGen(new NopInsnGen());
        registerInsnGen(new LineNumberInsnGen());
        registerInsnGen(new AssertObjectLiveInsnGen());
        registerInsnGen(new AssertInsnGen());
        registerInsnGen(new ObjectIsNullInsnGen());
        registerInsnGen(new VariantIsNilInsnGen());
        registerInsnGen(new GetVariantTypeInsnGen());
        registerInsnGen(new IsInstanceOfInsnGen());
        registerInsnGen(new BuiltinCastInsnGen());
        registerInsnGen(new ObjectCastInsnGen());
        registerInsnGen(new ControlFlowInsnGen());
        registerInsnGen(new NewDataInsnGen());
        registerInsnGen(new AssignInsnGen());
        registerInsnGen(new LoadPropertyInsnGen());
        registerInsnGen(new StorePropertyInsnGen());
        registerInsnGen(new OwnReleaseObjectInsnGen());
        registerInsnGen(new DestructInsnGen());
        registerInsnGen(new AwaitInsnGen());
        registerInsnGen(new PackUnpackVariantInsnGen());
        registerInsnGen(new CallGlobalInsnGen());
        registerInsnGen(new CallMethodInsnGen());
        registerInsnGen(new CallSuperMethodInsnGen());
        registerInsnGen(new CallStaticMethodInsnGen());
        registerInsnGen(new CallIntrinsicInsnGen());
        registerInsnGen(new ConstructInsnGen());
        registerInsnGen(new ContainerLiteralInsnGen());
        registerInsnGen(new OperatorInsnGen());
        registerInsnGen(new LoadStaticInsnGen());
        registerInsnGen(new StoreStaticInsnGen());
        registerInsnGen(new IndexLoadInsnGen());
        registerInsnGen(new IndexStoreInsnGen());
    }

    public CodegenContext ctx;
    public LirModule module;
    private CGenHelper helper;
    /// Validator for block layout and successor integrity.
    private final ControlFlowIntegrityValidator controlFlowValidator = new ControlFlowIntegrityValidator();
    /// Validator for compiler-only type leaks on ABI-like LIR surfaces.
    private final LirPublicAbiValidator publicAbiValidator = new LirPublicAbiValidator();
    /// Validator for lifecycle instruction usage restrictions.
    private final LifecycleInstructionRestrictionValidator lifecycleValidator = new LifecycleInstructionRestrictionValidator();

    private static void registerInsnGen(@NotNull CInsnGen<? extends LirInstruction> insnGen) {
        for (var opcode : insnGen.getInsnOpcodes()) {
            INSN_GENS.put(opcode, insnGen);
        }
    }

    private static boolean containsInstruction(@NotNull LirBasicBlock block, @NotNull LirInstruction instruction) {
        for (var existingInsn : block.getInstructions()) {
            if (existingInsn.checkEquals(instruction)) {
                return true;
            }
        }
        return false;
    }

    private void appendInsnIfAbsent(@NotNull LirFunctionDef func,
                                    @NotNull LirBasicBlock block,
                                    @NotNull LirInstruction instruction) {
        if (containsInstruction(block, instruction)) {
            LOGGER.warn("Function {} block {} already contains instruction {}, skip append.",
                    func.getName(),
                    block.id(),
                    instruction);
            return;
        }
        if (instruction instanceof ControlFlowInstruction controlFlowInstruction) {
            block.setTerminator(controlFlowInstruction);
            return;
        }
        block.appendNonTerminatorInstruction(instruction);
    }

    private @NotNull String resolvePrepareEntryTarget(@NotNull LirFunctionDef func, @NotNull LirBasicBlock prepareBB) {
        var terminator = prepareBB.getTerminator();
        if (terminator instanceof GotoInsn(var targetBbId) && !"__prepare__".equals(targetBbId)) {
            return targetBbId;
        }
        LOGGER.warn("Function {} already enters __prepare__ without a non-self goto target, keep __prepare__ as goto target.",
                func.getName());
        return "__prepare__";
    }

    private void generateDefaultGetterSetterInitialization() {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generateDefaultGetterSetter().");
        }
        for (var classDef : module.getClassDefs()) {
            var selfType = new GdObjectType(classDef.getName());
            for (var propertyDef : classDef.getProperties()) {
                // Static properties stay entirely out of instance member synthesis: no
                // getter/setter, and no `_field_init_` default helper either. Static default
                // values are materialized inline by the module-lifecycle defaults section,
                // so `initFunc != null` keeps meaning "source has an explicit initializer".
                if (propertyDef.isStatic()) {
                    continue;
                }
                if (propertyDef.getGetterFunc() == null) {
                    var getterName = "_field_getter_" + propertyDef.getName();
                    propertyDef.setGetterFunc(getterName);
                    var func = new LirFunctionDef(getterName);
                    func.setReturnType(propertyDef.getType());
                    func.addParameter(new LirParameterDef("self", selfType, null, func));
                    var tmpVar = func.createAndAddTmpVariable(propertyDef.getType());
                    var bb = new LirBasicBlock("entry");
                    func.addBasicBlock(bb);
                    bb.appendNonTerminatorInstruction(new LoadPropertyInsn(tmpVar.id(), propertyDef.getName(), "self"));
                    bb.setTerminator(new ReturnInsn(tmpVar.id()));
                    func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
                if (propertyDef.getSetterFunc() == null) {
                    var setterName = "_field_setter_" + propertyDef.getName();
                    propertyDef.setSetterFunc(setterName);
                    var func = new LirFunctionDef(setterName);
                    func.setReturnType(GdVoidType.VOID);
                    func.addParameter(new LirParameterDef("self", selfType, null, func));
                    func.addParameter(new LirParameterDef("value", propertyDef.getType(), null, func));

                    var bb = new LirBasicBlock("entry");
                    func.addBasicBlock(bb);
                    bb.appendNonTerminatorInstruction(new StorePropertyInsn(propertyDef.getName(), "self", "value"));
                    bb.setTerminator(new ReturnInsn(null));
                    func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
                if (propertyDef.getInitFunc() == null) {
                    var initName = "_field_init_" + propertyDef.getName();
                    propertyDef.setInitFunc(initName);
                    var func = new LirFunctionDef(initName);
                    func.setHidden(true);
                    func.setReturnType(propertyDef.getType());
                    func.addParameter(new LirParameterDef("self", selfType, null, func));
                    var tmpVar = func.createAndAddTmpVariable(propertyDef.getType());
                    var bb = new LirBasicBlock("entry");
                    func.addBasicBlock(bb);
                    switch (propertyDef.getType()) {
                        case GdCompilerType _ -> throw new IllegalStateException(
                                "compiler-only type leaked into property initializer: " + propertyDef.getType().getTypeName()
                        );
                        case GdObjectType _ -> bb.appendNonTerminatorInstruction(new LiteralNullInsn(tmpVar.id()));
                        case GdVariantType _, GdNilType _ ->
                                bb.appendNonTerminatorInstruction(new LiteralNilInsn(tmpVar.id()));
                        case GdBoolType _ -> bb.appendNonTerminatorInstruction(new LiteralBoolInsn(tmpVar.id(), false));
                        case GdIntType _ -> bb.appendNonTerminatorInstruction(new LiteralIntInsn(tmpVar.id(), 0));
                        case GdFloatType _ -> bb.appendNonTerminatorInstruction(new LiteralFloatInsn(tmpVar.id(), 0.0));
                        case GdStringType _ ->
                                bb.appendNonTerminatorInstruction(new LiteralStringInsn(tmpVar.id(), ""));
                        case GdStringNameType _ ->
                                bb.appendNonTerminatorInstruction(new LiteralStringNameInsn(tmpVar.id(), ""));
                        case GdArrayType gdArrayType ->
                                bb.appendNonTerminatorInstruction(new ConstructArrayInsn(tmpVar.id(), gdArrayType.getValueType().getTypeName()));
                        case GdPackedArrayType _ ->
                                bb.appendNonTerminatorInstruction(new ConstructArrayInsn(tmpVar.id(), null));
                        case GdDictionaryType gdDictionaryType ->
                                bb.appendNonTerminatorInstruction(new ConstructDictionaryInsn(tmpVar.id(), gdDictionaryType.getKeyType().getTypeName(), gdDictionaryType.getValueType().getTypeName()));
                        default -> bb.appendNonTerminatorInstruction(new ConstructBuiltinInsn(tmpVar.id(), List.of()));
                    }
                    bb.setTerminator(new ReturnInsn(tmpVar.id()));
                    func.setEntryBlockId("entry");
                    classDef.addFunction(func);
                }
            }
        }
    }

    /// Copies `_capture->name` into the matching lambda local before `__prepare__`.
    /// Capture slots are excluded from default construction, so this is their first write.
    /// Coroutine lambdas skip this entirely: their body has no `_capture` parameter — the start
    /// thunk copies captures into typed frame fields at the call boundary instead,
    /// and the body reads/writes the frame fields directly via `CBodyBuilder`.
    private void emitLambdaCapturePrologue(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirClassDef clazz,
            @NotNull LirFunctionDef func
    ) {
        if (func.isCoroutine()) {
            return;
        }
        if (!func.isLambda() || func.getCaptureCount() == 0) {
            return;
        }
        for (var capture : func.getCaptures().values()) {
            var local = func.getVariableById(capture.getName());
            if (local == null) {
                throw new IllegalStateException(
                        "Lambda '" + clazz.getName() + "." + func.getName()
                                + "' is missing the local slot for capture '" + capture.getName() + "'"
                );
            }
            var sourceExpr = "_capture->" + capture.getName();
            if (capture.getType() instanceof GdObjectType objectType) {
                bodyBuilder.applyPropertyInitializerFirstWrite(
                        "$" + local.id(),
                        objectType,
                        sourceExpr,
                        objectType,
                        CBodyBuilder.PtrKind.FAT_PTR,
                        CBodyBuilder.OwnershipKind.BORROWED
                );
            } else {
                bodyBuilder.appendRaw("$" + local.id() + " = "
                        + helper.renderLambdaCaptureCopyExpr(capture.getType(), sourceExpr)
                        + ";\n");
            }
        }
    }

    private void generateFunctionPrepareBlock() {
        for (var classDef : module.getClassDefs()) {
            for (var func : classDef.getFunctions()) {
                var prepareBB = func.getBasicBlock("__prepare__");
                if (prepareBB == null) {
                    prepareBB = new LirBasicBlock("__prepare__");
                    func.addBasicBlock(prepareBB);
                }
                // initialize variables
                var parameterNames = func.getParameters().stream()
                        .map(ParameterDef::getName)
                        .collect(HashSet<String>::new, HashSet::add, HashSet::addAll);
                for (var variable : func.getVariables().values()) {
                    if (parameterNames.contains(variable.id())) {
                        continue;
                    }
                    if (func.getCapture(variable.id()) != null) {
                        continue;
                    }
                    if (variable.ref()) {
                        continue;
                    }
                    // Discarded void-return calls no longer publish result slots, but backend still
                    // skips any stray void variables so invalid IR fails at the real opcode/value
                    // contract boundary instead of drifting into a fake constructor path.
                    if (variable.type() instanceof GdVoidType) {
                        continue;
                    }
                    var initInsn = switch (variable.type()) {
                        case GdCompilerType compilerType -> new CallIntrinsicInsn(
                                variable.id(),
                                helper.renderCompilerOnlyInitFunctionName(compilerType),
                                List.of()
                        );
                        case GdObjectType _ -> new LiteralNullInsn(variable.id());
                        case GdVariantType _, GdNilType _ -> new LiteralNilInsn(variable.id());
                        case GdBoolType _ -> new LiteralBoolInsn(variable.id(), false);
                        case GdIntType _ -> new LiteralIntInsn(variable.id(), 0);
                        case GdFloatType _ -> new LiteralFloatInsn(variable.id(), 0.0);
                        case GdStringType _ -> new LiteralStringInsn(variable.id(), "");
                        case GdStringNameType _ -> new LiteralStringNameInsn(variable.id(), "");
                        case GdArrayType gdArrayType ->
                                new ConstructArrayInsn(variable.id(), gdArrayType.getValueType().getTypeName());
                        case GdPackedArrayType _ -> new ConstructArrayInsn(variable.id(), null);
                        case GdDictionaryType gdDictionaryType ->
                                new ConstructDictionaryInsn(variable.id(), gdDictionaryType.getKeyType().getTypeName(), gdDictionaryType.getValueType().getTypeName());
                        default -> new ConstructBuiltinInsn(variable.id(), List.of());
                    };
                    appendInsnIfAbsent(func, prepareBB, initInsn);
                }
                var funcEntry = func.getEntryBlockId();
                var targetEntry = "__prepare__".equals(funcEntry)
                        ? resolvePrepareEntryTarget(func, prepareBB)
                        : funcEntry;
                appendInsnIfAbsent(func, prepareBB, new GotoInsn(targetEntry));
                func.setEntryBlockId("__prepare__");
            }
        }
    }

    /// `initFunc == null` means backend still owns default-value helper synthesis. Once a property
    /// points at a named init function, backend only accepts an already materialized executable body.
    private void validatePropertyInitFunctionsReadyForCodegen() {
        for (var classDef : module.getClassDefs()) {
            for (var propertyDef : classDef.getProperties()) {
                // Static properties without a source initializer carry `initFunc == null` because
                // no default helper is synthesized for them; their defaults are materialized
                // inline by the module lifecycle defaults section, so there is nothing to validate.
                if (propertyDef.isStatic() && propertyDef.getInitFunc() == null) {
                    continue;
                }
                validatePropertyInitFunctionReadyForCodegen(classDef, propertyDef);
            }
        }
    }

    private void validatePropertyInitFunctionReadyForCodegen(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef
    ) {
        var function = resolvePropertyInitFunction(classDef, propertyDef);
        validatePropertyInitFunctionSignature(classDef, propertyDef, function);
        if (function.getBasicBlockCount() == 0 || function.getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references shell-only init function '"
                            + function.getName()
                            + "'; property init must be fully lowered before backend codegen"
            );
        }
        if (!function.hasBasicBlock(function.getEntryBlockId())) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with invalid entry block ID: "
                            + function.getEntryBlockId()
            );
        }
    }

    private @NotNull LirFunctionDef resolvePropertyInitFunction(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef
    ) {
        var initFuncName = propertyDef.getInitFunc();
        if (initFuncName == null) {
            throw new IllegalStateException(
                    "Property '" + classDef.getName() + "." + propertyDef.getName() + "' does not define initFunc"
            );
        }
        var matches = classDef.getFunctions().stream()
                .filter(function -> function.getName().equals(initFuncName))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "Property init function '"
                            + classDef.getName()
                            + "."
                            + initFuncName
                            + "' referenced by property '"
                            + propertyDef.getName()
                            + "' does not exist"
            );
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one property init function '"
                            + classDef.getName()
                            + "."
                            + initFuncName
                            + "' for property '"
                            + propertyDef.getName()
                            + "', but found "
                            + matches.size()
            );
        }
        return matches.getFirst();
    }

    /// Property-init helpers are always internal single-return helpers with the owning-class `self`
    /// parameter. Backend keeps this contract explicit so template rendering never has to guess
    /// whether a named `initFunc` still needs repair.
    private void validatePropertyInitFunctionSignature(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef,
            @NotNull LirFunctionDef function
    ) {
        if (!function.isHidden()) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references non-hidden init function '"
                            + function.getName()
                            + "'; property init helpers must stay internal to backend/template wiring"
            );
        }
        if (!function.getReturnType().equals(propertyDef.getType())) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with mismatched return type "
                            + function.getReturnType().getTypeName()
                            + "; expected "
                            + propertyDef.getType().getTypeName()
            );
        }
        // Static property init helpers follow the static shell contract: hidden (checked above),
        // explicitly static, zero parameters — they run without any instance and are invoked
        // from the module lifecycle path to write shared storage.
        if (propertyDef.isStatic()) {
            if (!function.isStatic()) {
                throw new IllegalStateException(
                        "Property '"
                                + classDef.getName()
                                + "."
                                + propertyDef.getName()
                                + "' references non-static init function '"
                                + function.getName()
                                + "'; static property init helpers must be static"
                );
            }
            if (function.getParameterCount() != 0) {
                throw new IllegalStateException(
                        "Property '"
                                + classDef.getName()
                                + "."
                                + propertyDef.getName()
                                + "' references static init function '"
                                + function.getName()
                                + "' with "
                                + function.getParameterCount()
                                + " parameters; expected zero parameters"
                );
            }
            return;
        }
        if (function.getParameterCount() != 1) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with "
                            + function.getParameterCount()
                            + " parameters; expected exactly one owning-class self parameter"
            );
        }

        var selfParameter = function.getParameter(0);
        if (selfParameter == null
                || !selfParameter.getName().equals("self")
                || !selfParameter.getType().equals(new GdObjectType(classDef.getName()))) {
            throw new IllegalStateException(
                    "Property '"
                            + classDef.getName()
                            + "."
                            + propertyDef.getName()
                            + "' references init function '"
                            + function.getName()
                            + "' with invalid self parameter; expected `self: "
                            + classDef.getName()
                            + "`"
            );
        }
    }

    private void ensureFunctionFinallyBlock() {
        for (var classDef : module.getClassDefs()) {
            for (var func : classDef.getFunctions()) {
                var finallyBB = func.getBasicBlock("__finally__");
                if (finallyBB == null) {
                    finallyBB = new LirBasicBlock("__finally__");
                    func.addBasicBlock(finallyBB);
                }

                var parameterNames = func.getParameters().stream()
                        .map(ParameterDef::getName)
                        .collect(HashSet<String>::new, HashSet::add, HashSet::addAll);
                for (var variable : func.getVariables().values()) {
                    if (!shouldInsertAutoGeneratedFinallyDestruct(func, variable, parameterNames)) {
                        continue;
                    }
                    appendInsnIfAbsent(func, finallyBB,
                            new DestructInsn(variable.id(), LifecycleProvenance.AUTO_GENERATED));
                }
                if (func.getReturnType() instanceof GdVoidType) {
                    appendInsnIfAbsent(func, finallyBB, new ReturnInsn(null));
                } else {
                    appendInsnIfAbsent(func, finallyBB, new ReturnInsn("_return_val"));
                }
            }
        }
    }

    /// `__finally__` auto-cleanup is slot-based: it only targets managed local slots still owned by the
    /// current function. `_return_val` stays outside this set because it is the hidden return-publish
    /// boundary declared by `CBodyBuilder`, not a normal LIR variable slot. Coroutine lambda captures
    /// are excluded for coroutine bodies: their owning storage is the typed frame field, destroyed
    /// exactly once by `free_instance` (same discipline as coroutine parameter fields, spec §3.10);
    /// synchronous lambda capture locals stay ordinary owning slots and keep their auto-destruct.
    private boolean shouldInsertAutoGeneratedFinallyDestruct(@NotNull LirFunctionDef func,
                                                             @NotNull LirVariable variable,
                                                             @NotNull Set<String> parameterNames) {
        if ("_return_val".equals(variable.id())) {
            return false;
        }
        if (parameterNames.contains(variable.id()) || variable.ref()) {
            return false;
        }
        if (func.isCoroutine() && func.getCapture(variable.id()) != null) {
            return false;
        }
        if (!variable.type().isDestroyable()) {
            return false;
        }
        if (variable.type() instanceof GdObjectType objectType) {
            var refCountedStatus = ctx.classRegistry().getRefCountedStatus(objectType);
            // Godot does not auto-free non-RefCounted objects at local scope exit.
            // They stay under explicit user-managed lifetime (`free`, `queue_free`, etc.).
            return refCountedStatus != RefCountedStatus.NO;
        }
        return true;
    }

    /// Builds the coroutine frame context for a `__coro_body` render, or null for ordinary
    /// synchronous functions. The state struct name is shared with the state-class templates.
    private @Nullable CCoroutineFrameContext newCoroutineFrameContext(@NotNull LirClassDef clazz,
                                                                      @NotNull LirFunctionDef func) {
        if (!func.isCoroutine()) {
            return null;
        }
        return new CCoroutineFrameContext(helper.renderCoroStateClassName(clazz, func));
    }

    public @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                            @NotNull LirFunctionDef func) {
        return generateFuncBody(clazz, func, GodotBindingUsageBuffer.noOp());
    }

    @SuppressWarnings("unchecked")
    @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                     @NotNull LirFunctionDef func,
                                     @NotNull GodotBindingUsageBuffer usageBuffer) {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generateBlock().");
        }
        controlFlowValidator.validateFunction(func);
        lifecycleValidator.validateFunction(ctx, func);
        // Check if the entry block is valid
        if (!func.hasBasicBlock(func.getEntryBlockId())) {
            throw new IllegalArgumentException("Function " + func.getName() + " has invalid entry block ID: " + func.getEntryBlockId());
        }
        var bodyBuilder = new CBodyBuilder(helper, clazz, func, usageBuffer, newCoroutineFrameContext(clazz, func));
        emitLambdaCapturePrologue(bodyBuilder, clazz, func);
        // generate blocks
        bodyBuilder.appendRaw("goto " + func.getEntryBlockId() + ";\n");
        for (var bb : func) {
            bodyBuilder.beginBasicBlock(bb.id());
            for (int i = 0; i < bb.getInstructionCount(); i++) {
                var insn = bb.getInstruction(i);
                CInsnGen<LirInstruction> insnGen = (CInsnGen<LirInstruction>) INSN_GENS.get(insn.opcode());
                if (insnGen == null) {
                    throw new UnsupportedOperationException("Unsupported instruction opcode: " + insn.opcode().opcode());
                }
                bodyBuilder.setCurrentPosition(bb, i, insn);
                insnGen.generateCCode(bodyBuilder);
            }
        }
        return bodyBuilder.build();
    }

    @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                     @NotNull LirFunctionDef func,
                                     @NotNull GodotBindingUsageSession godotBindingUsageSession) {
        var usageBuffer = godotBindingUsageSession.newFunctionBuffer();
        var body = generateFuncBody(clazz, func, usageBuffer);
        godotBindingUsageSession.commit(usageBuffer);
        return body;
    }

    /// Renders the constructor-time property initializer apply body.
    /// The init helper still only produces a value; this method owns the direct backing-field first-write
    /// route so property initialization keeps unified slot-write semantics without becoming a setter call.
    private @NotNull String generatePropertyInitApplyBody(@NotNull LirClassDef clazz,
                                                          @NotNull LirPropertyDef property,
                                                          @NotNull GodotBindingUsageBuffer usageBuffer) {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generating property init apply code.");
        }
        // Instance-only route: static property writes go through the module-lifecycle static
        // sections (backing variables), never through `self->field` apply helpers.
        if (property.isStatic()) {
            throw new IllegalStateException(
                    "Property '" + clazz.getName() + "." + property.getName()
                            + "' is static; static properties have no constructor-time apply helper"
            );
        }
        var initFunction = resolvePropertyInitFunction(clazz, property);
        var bodyBuilder = new CBodyBuilder(
                helper,
                clazz,
                initFunction,
                usageBuffer
        );
        // Internal init helpers take owner fat self; the apply wrapper still receives Class*.
        var ownerType = new GdObjectType(clazz.getName());
        var fatType = helper.renderObjectFatPtrStorageType(ownerType);
        var selfFatArg = fatType + "_from_raw(" + clazz.getName() + "_object_ptr(self))";
        bodyBuilder.applyPropertyInitializerFirstWrite(
                "self->" + property.getName(),
                property.getType(),
                clazz.getName() + "_" + initFunction.getName() + "(" + selfFatArg + ")",
                initFunction.getReturnType(),
                initFunction.getReturnType() instanceof GdObjectType
                        ? CBodyBuilder.PtrKind.FAT_PTR
                        : CBodyBuilder.PtrKind.NON_OBJECT,
                // Property-init helpers are a dedicated fresh-producer entry: the apply helper must
                // consume the returned object directly instead of re-owning the field write.
                CBodyBuilder.OwnershipKind.OWNED
        );
        return bodyBuilder.build();
    }

    @NotNull String generatePropertyInitApplyBody(@NotNull LirClassDef clazz,
                                                  @NotNull LirPropertyDef property,
                                                  @NotNull GodotBindingUsageSession godotBindingUsageSession) {
        var buffer = godotBindingUsageSession.newFunctionBuffer();
        var body = generatePropertyInitApplyBody(clazz, property, buffer);
        godotBindingUsageSession.commit(buffer);
        return body;
    }

    // ==== Static property module lifecycle ====

    private static @NotNull CBodyBuilder.PtrKind ptrKindOf(@NotNull GdType type) {
        return type instanceof GdObjectType ? CBodyBuilder.PtrKind.FAT_PTR : CBodyBuilder.PtrKind.NON_OBJECT;
    }

    /// Renders the per-class static defaults section body: every static property is
    /// first-written with its materialized type default in declaration order. Backing variables
    /// are zero-initialized file-scope storage, so these writes must never destroy an old value.
    private @NotNull String generateStaticDefaultsBody(@NotNull LirClassDef clazz,
                                                       @NotNull GodotBindingUsageBuffer usageBuffer) {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generating static defaults code.");
        }
        // Shell function context: the builder only needs an owner for diagnostics and temp naming;
        // no LIR instructions are executed for this synthesized section.
        var shellFunc = new LirFunctionDef(helper.renderStaticDefaultsSymbol(clazz.getName()));
        var bodyBuilder = new CBodyBuilder(helper, clazz, shellFunc, usageBuffer);
        for (var property : clazz.getProperties()) {
            if (!property.isStatic()) {
                continue;
            }
            var propertyType = property.getType();
            if (propertyType instanceof GdCompilerType) {
                throw new IllegalStateException(
                        "compiler-only type leaked into static property defaults: "
                                + clazz.getName() + "." + property.getName()
                                + " (" + propertyType.getTypeName() + ")"
                );
            }
            var backingExpr = helper.renderStaticBackingSymbol(clazz.getName(), property.getName());
            if (propertyType instanceof GdContainerType) {
                // Containers go through the builtin constructor so typed Array/Dictionary keep
                // their element-type metadata (`renderDefaultValueExprInC` only yields untyped
                // empties). The fresh temp is uninitialized, so the constructor write performs
                // no old-value destroy.
                var temp = bodyBuilder.newTempVariable("static_default", propertyType);
                bodyBuilder.declareTempVar(temp);
                helper.builtinBuilder().constructBuiltin(bodyBuilder, temp, List.of());
                bodyBuilder.applyPropertyInitializerFirstWrite(
                        backingExpr, propertyType, temp.name(), propertyType,
                        ptrKindOf(propertyType), CBodyBuilder.OwnershipKind.OWNED
                );
                // The temp's value moved into the backing slot; nothing further to destroy.
            } else {
                bodyBuilder.applyPropertyInitializerFirstWrite(
                        backingExpr, propertyType, helper.renderDefaultValueExprInC(propertyType), propertyType,
                        ptrKindOf(propertyType), CBodyBuilder.OwnershipKind.OWNED
                );
            }
        }
        return bodyBuilder.build();
    }

    @NotNull String generateStaticDefaultsBody(@NotNull LirClassDef clazz,
                                               @NotNull GodotBindingUsageSession godotBindingUsageSession) {
        var buffer = godotBindingUsageSession.newFunctionBuffer();
        var body = generateStaticDefaultsBody(clazz, buffer);
        godotBindingUsageSession.commit(buffer);
        return body;
    }

    /// Renders the per-class static initializers section body: for each static property
    /// with a source initializer (`initFunc != null` by construction), calls the hidden static
    /// `_field_init_<name>()` helper and overwrites the backing variable. The overwrite runs
    /// through `moveOwnedCallIntoSlot`, so the already-materialized default is destroyed/released
    /// exactly once and the helper result moves in without an extra copy.
    private @NotNull String generateStaticInitializersBody(@NotNull LirClassDef clazz,
                                                           @NotNull GodotBindingUsageBuffer usageBuffer) {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generating static initializers code.");
        }
        var shellFunc = new LirFunctionDef(helper.renderStaticInitializersSymbol(clazz.getName()));
        var bodyBuilder = new CBodyBuilder(helper, clazz, shellFunc, usageBuffer);
        for (var property : clazz.getProperties()) {
            if (!property.isStatic() || property.getInitFunc() == null) {
                continue;
            }
            var initFunction = resolvePropertyInitFunction(clazz, property);
            var backingExpr = helper.renderStaticBackingSymbol(clazz.getName(), property.getName());
            // Init helpers are dedicated fresh producers: the returned OWNED value moves into the
            // backing slot after the materialized default is destroyed (destroy-then-move for
            // value semantics; capture → assign → consume → release for objects).
            bodyBuilder.moveOwnedCallIntoSlot(
                    bodyBuilder.targetOfExpr(backingExpr, property.getType()),
                    clazz.getName() + "_" + initFunction.getName() + "()",
                    initFunction.getReturnType()
            );
        }
        return bodyBuilder.build();
    }

    @NotNull String generateStaticInitializersBody(@NotNull LirClassDef clazz,
                                                   @NotNull GodotBindingUsageSession godotBindingUsageSession) {
        var buffer = godotBindingUsageSession.newFunctionBuffer();
        var body = generateStaticInitializersBody(clazz, buffer);
        godotBindingUsageSession.commit(buffer);
        return body;
    }

    /// Renders the `deinitialize()` cleanup for one class's static backing variables: destroyable
    /// values are released/destroyed in reverse declaration order via the shared managed-storage
    /// free formula (`CGenHelper.renderStaticBackingDestroyStmt`). The template calls this for
    /// classes in reverse initialization order before the runtime registries are torn down.
    private @NotNull String generateStaticDeinitializeBody(@NotNull LirClassDef clazz) {
        var body = new StringBuilder();
        var staticProperties = clazz.getProperties().stream().filter(LirPropertyDef::isStatic).toList();
        for (var property : staticProperties.reversed()) {
            var stmt = helper.renderStaticBackingDestroyStmt(
                    property.getType(),
                    helper.renderStaticBackingSymbol(clazz.getName(), property.getName())
            );
            if (!stmt.isEmpty()) {
                body.append(stmt).append("\n");
            }
        }
        return body.toString();
    }

    /// All module classes ordered base-before-derived along the module's inheritance topology:
    /// a class never precedes its in-module superclass. Two consumers depend on this order —
    /// `entry.h` struct definitions embed the parent wrapper by value (`Parent _super`), so the
    /// parent type must be complete first, and Godot refuses to register an extension class whose
    /// parent extension class is not registered yet. Superclasses outside the module (engine or
    /// native roots) impose no constraint. Each pass emits, in module (`LirModule.classDefs`)
    /// order, every class whose in-module superclass is already emitted, so the result is
    /// deterministic for identical input; a class blocked behind its superclass chain can still
    /// land after unrelated classes that follow it in module order, so only the
    /// base-before-derived guarantee is contractual.
    private @NotNull List<LirClassDef> computeInheritanceClassOrder() {
        var classByName = new HashMap<String, LirClassDef>();
        for (var classDef : module.getClassDefs()) {
            classByName.put(classDef.getName(), classDef);
        }
        var remaining = new ArrayList<>(module.getClassDefs());
        var ordered = new ArrayList<LirClassDef>(remaining.size());
        var emittedNames = new HashSet<String>();
        while (!remaining.isEmpty()) {
            var progressed = false;
            for (var iterator = remaining.iterator(); iterator.hasNext(); ) {
                var candidate = iterator.next();
                var superDef = classByName.get(candidate.getSuperName());
                if (superDef == null || emittedNames.contains(superDef.getName())) {
                    ordered.add(candidate);
                    emittedNames.add(candidate.getName());
                    iterator.remove();
                    progressed = true;
                }
            }
            // The frontend rejects inheritance cycles before lowering, so a stall here means a
            // hand-built or corrupted module reached the backend; fail fast instead of emitting
            // silently unordered output.
            if (!progressed) {
                throw new IllegalStateException(
                        "Inheritance cycle among module classes: "
                                + remaining.stream().map(LirClassDef::getName).toList()
                );
            }
        }
        return List.copyOf(ordered);
    }

    /// Classes that declare at least one static property, filtered from the module-wide
    /// base-before-derived inheritance order for the global two-phase static initialization.
    /// The only contractual guarantee is base-before-derived, including transitive ancestors
    /// reached through non-static intermediates (`A -> B -> C` orders `A` after `C` even when
    /// `B` declares no statics); static classes unrelated by inheritance are NOT guaranteed to
    /// keep their module-order relative positions, but the filtered result stays deterministic
    /// for identical input.
    private @NotNull List<LirClassDef> computeStaticInitClassOrder(@NotNull List<LirClassDef> inheritanceOrderedClassDefs) {
        return inheritanceOrderedClassDefs.stream()
                .filter(classDef -> classDef.getProperties().stream().anyMatch(LirPropertyDef::isStatic))
                .toList();
    }

    /// Module-wide C file-scope symbol conflict validation. Collects the
    /// symbols this backend is responsible for — static backing variables, per-class static
    /// defaults/initializers entries, all `${class}_${func}` functions (including synthesized
    /// getter/setter/init helpers), property-init apply helpers, and the template-fixed per-class
    /// machinery symbols — and fails fast on any duplicate, reporting both emission sources.
    /// Coroutine/lambda helper families keep their existing risk level and are not re-spelled here.
    private void validateFileScopeSymbolsDisjoint() {
        var symbolSources = new HashMap<String, String>();
        // Fixed module-level symbols emitted by entry.c/entry.h themselves; a user class named
        // e.g. `gdextension` with a function `entry` would otherwise collide with the exported
        // entry point without being reported here.
        registerFileScopeSymbol(symbolSources, "gdextension_entry", "GDExtension entry point (entry.c.ftl)");
        registerFileScopeSymbol(symbolSources, "initialize", "module initialize callback (entry.c.ftl)");
        registerFileScopeSymbol(symbolSources, "deinitialize", "module deinitialize callback (entry.c.ftl)");
        registerFileScopeSymbol(symbolSources, "class_library", "class library storage (entry.h.ftl)");
        for (var classDef : module.getClassDefs()) {
            var className = classDef.getName();
            for (var suffix : List.of(
                    "_class_bind_methods", "_class_create_instance", "_class_free_instance",
                    "_class_constructor", "_class_destructor", "_class_notification",
                    "_class_get_virtual_with_data", "_class_call_virtual_with_data",
                    "_class_binding_callbacks", "_object_ptr", "_set_object_ptr")) {
                registerFileScopeSymbol(symbolSources, className + suffix, "class machinery of '" + className + "'");
            }
            // The vtable accessor exists only for slot-introducing classes, so it is registered
            // conditionally: a user method named `class_vtable` stays legal on slotless classes
            // but conflicts here exactly like the machinery suffixes above once emitted.
            if (helper.vtablePlanner().introducesSlot(className)) {
                registerFileScopeSymbol(symbolSources, helper.renderVtableAccessorName(className),
                        "vtable accessor of '" + className + "'");
            }
            for (var function : classDef.getFunctions()) {
                registerFileScopeSymbol(symbolSources, className + "_" + function.getName(),
                        "function '" + className + "." + function.getName() + "'");
                if (!function.isHidden() && !function.isLambda() && helper.countDefaultSlots(function) > 0) {
                    registerFileScopeSymbol(symbolSources,
                            helper.renderDefaultUserdataInstanceName(classDef, function),
                            "default userdata of '" + className + "." + function.getName() + "'");
                }
            }
            var hasStaticProperties = false;
            for (var property : classDef.getProperties()) {
                if (property.isStatic()) {
                    hasStaticProperties = true;
                    registerFileScopeSymbol(symbolSources,
                            helper.renderStaticBackingSymbol(className, property.getName()),
                            "static property backing of '" + className + "." + property.getName() + "'");
                } else {
                    registerFileScopeSymbol(symbolSources,
                            helper.renderPropertyInitApplyHelperName(classDef, property),
                            "property-init apply helper of '" + className + "." + property.getName() + "'");
                }
            }
            if (hasStaticProperties) {
                registerFileScopeSymbol(symbolSources, helper.renderStaticDefaultsSymbol(className),
                        "static defaults entry of '" + className + "'");
                registerFileScopeSymbol(symbolSources, helper.renderStaticInitializersSymbol(className),
                        "static initializers entry of '" + className + "'");
            }
        }
    }

    private static void registerFileScopeSymbol(
            @NotNull Map<String, String> symbolSources,
            @NotNull String symbol,
            @NotNull String source
    ) {
        var previous = symbolSources.putIfAbsent(symbol, source);
        if (previous != null) {
            throw new IllegalStateException(
                    "C file-scope symbol conflict: '" + symbol + "' is emitted by both "
                            + previous + " and " + source
                            + "; rename the conflicting class, function, or property"
            );
        }
    }


    @Override
    public List<GeneratedFile> generate() {
        if (ctx == null || module == null) {
            throw new IllegalStateException("CCodegen not prepared. Call prepare() before generate().");
        }
        // Validate ABI surfaces before backend synthesizes helpers or touches outward metadata.
        publicAbiValidator.validateModule(module);
        this.generateDefaultGetterSetterInitialization();
        this.validatePropertyInitFunctionsReadyForCodegen();
        // Runs after getter/setter/init synthesis so synthesized names join the conflict surface.
        this.validateFileScopeSymbolsDisjoint();
        this.generateFunctionPrepareBlock();
        this.ensureFunctionFinallyBlock();
        for (var classDef : module.getClassDefs()) {
            for (var function : classDef.getFunctions()) {
                controlFlowValidator.validateFunction(function);
                lifecycleValidator.validateFunction(ctx, function);
            }
        }
        var inheritanceOrderedClassDefs = computeInheritanceClassOrder();
        var staticInitClassDefs = computeStaticInitClassOrder(inheritanceOrderedClassDefs);
        try {
            var usageSession = GodotBindingUsageSession.forRegistry(ctx.classRegistry());
            // Template-visible registrations are committed only after `entry.c` renders successfully.
            var templateUsageBuffer = usageSession.newFunctionBuffer();
            var bodyRender = new GenerateRenderFacade(
                    (classDef, func) -> generateFuncBody(classDef, func, usageSession),
                    (classDef, property) -> generatePropertyInitApplyBody(classDef, property, usageSession),
                    classDef -> generateStaticDefaultsBody(classDef, usageSession),
                    classDef -> generateStaticInitializersBody(classDef, usageSession),
                    this::generateStaticDeinitializeBody,
                    templateUsageBuffer
            );
            var cTplCtx = Map.of(
                    "module", module,
                    "helper", helper,
                    "bodyRender", bodyRender,
                    "staticInitClassDefs", staticInitClassDefs,
                    "inheritanceOrderedClassDefs", inheritanceOrderedClassDefs
            );
            var cSrc = TemplateLoader.renderFromClasspath("template_451/entry.c.ftl", cTplCtx);
            usageSession.commit(templateUsageBuffer);
            var usedEngineMethods = usageSession.engineMethods();
            var usedEngineConstructors = usageSession.engineConstructors();
            var usedModuleLocalBindings = usageSession.moduleLocalBindings();
            // Header guards must be real C identifiers: display-oriented module names may contain
            // spaces or punctuation, which would otherwise collide every generated header behind
            // the same first `#ifndef` identifier and silently suppress their contents.
            var headerGuardPrefix = GodotBindingSupport.cIdentifier(
                    "GDEXTENSION_" + module.getModuleName().toUpperCase(Locale.ROOT)
            );
            var objectFatPtrSpecs = CObjectFatPtrCollector.collect(
                    module,
                    ctx.classRegistry(),
                    usedEngineMethods,
                    usedEngineConstructors,
                    usedModuleLocalBindings
            );
            var bindTplCtx = Map.of(
                    "module", module,
                    "helper", helper,
                    "headerGuardPrefix", headerGuardPrefix,
                    "usedEngineMethods", usedEngineMethods,
                    "usedEngineConstructors", usedEngineConstructors,
                    "usedModuleLocalBindings", usedModuleLocalBindings
            );
            var engineMethodBindsSrc = TemplateLoader.renderFromClasspath(
                    "template_451/engine_method_binds.h.ftl",
                    bindTplCtx
            );
            var objectFatPtrTypesTplCtx = Map.of(
                    "module", module,
                    "helper", helper,
                    "headerGuardPrefix", headerGuardPrefix,
                    "objectFatPtrSpecs", objectFatPtrSpecs
            );
            var objectFatPtrTypesSrc = TemplateLoader.renderFromClasspath(
                    "template_451/object_fat_ptr_types.h.ftl",
                    objectFatPtrTypesTplCtx
            );
            var hTplCtx = Map.of(
                    "module", module,
                    "helper", helper,
                    "headerGuardPrefix", headerGuardPrefix,
                    "inheritanceOrderedClassDefs", inheritanceOrderedClassDefs
            );
            var hSrc = TemplateLoader.renderFromClasspath("template_451/entry.h.ftl", hTplCtx);
            cSrc = CCodeFormatter.format(cSrc);
            engineMethodBindsSrc = CCodeFormatter.format(engineMethodBindsSrc);
            objectFatPtrTypesSrc = CCodeFormatter.format(objectFatPtrTypesSrc);
            hSrc = CCodeFormatter.format(hSrc);

            var cBytes = cSrc.getBytes(StandardCharsets.UTF_8);
            var engineMethodBindsBytes = engineMethodBindsSrc.getBytes(StandardCharsets.UTF_8);
            var objectFatPtrTypesBytes = objectFatPtrTypesSrc.getBytes(StandardCharsets.UTF_8);
            var hBytes = hSrc.getBytes(StandardCharsets.UTF_8);

            var cFile = new GeneratedFile(cBytes, "entry.c");
            var engineMethodBindsFile = new GeneratedFile(engineMethodBindsBytes, "engine_method_binds.h");
            var objectFatPtrTypesFile = new GeneratedFile(objectFatPtrTypesBytes, "object_fat_ptr_types.h");
            var hFile = new GeneratedFile(hBytes, "entry.h");
            return List.of(cFile, engineMethodBindsFile, objectFatPtrTypesFile, hFile);
        } catch (IOException | TemplateException e) {
            throw new RuntimeException("Failed to generate C code: " + e.getMessage(), e);
        }
    }

    @Override
    public void prepare(@NotNull CodegenContext ctx, @NotNull LirModule module) {
        this.ctx = ctx;
        this.module = module;
        var registry = ctx.classRegistry();
        for (var classDef : module.getClassDefs()) {
            registry.addGdccClass(classDef);
        }
        this.helper = new CGenHelper(ctx, module.getClassDefs());
    }
}
