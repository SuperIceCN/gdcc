package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.exception.CodegenException;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Plans the vtable layout of the module's GDCC classes (pure analysis; emits no C).
///
/// A GDCC instance method `m` declared by class `C` is *polymorphic* when a proper in-module
/// descendant of `C` declares a signature-compatible instance method with the same name; only
/// polymorphic methods occupy vtable slots. `slots(C)` starts from an ordered copy of
/// `slots(parent)` (which yields prefix compatibility across generations) and appends, in
/// declaration order, one slot per newly polymorphic method; a compatible override reuses the
/// inherited slot instead of appending. Each per-class slot entry records the slot introducer
/// and the *final overrider* — the nearest declaration on the chain from that class up to the
/// introducer — which later decides between a direct implementation pointer and an override
/// trampoline during C emission.
///
/// Role vocabulary shared by all consumers:
/// - `slotted(C)`: `slots(C)` is non-empty (inherited slots included);
/// - `introducesSlot(C)`: `C` appended at least one new slot of its own;
/// - `passThrough(C)`: slotted but neither introduces nor overrides any slot.
/// A slotless class inside a hierarchy that carries slots elsewhere is a *side branch*; a
/// hierarchy is one GDCC root class plus all of its in-module descendants.
///
/// Fail-fast rules enforced at construction:
/// - a descendant declaring a same-named instance method with an incompatible signature is a
///   compile-time error reporting both class names and the method, because that declaration
///   would otherwise be installed into the ancestor's slot and invoked through the wrong
///   signature. `_init`, static, hidden and lambda methods never occupy slots and never raise
///   this conflict; such an excluded declaration also breaks the override chain for deeper
///   subclasses (a fresh same-named declaration below it is not an override);
/// - a concrete (non-abstract) class whose inherited slot resolves to an abstract declaration
///   has no callable implementation and is rejected; abstract classes keep a NULL entry.
///
/// Tolerance rule: a GDCC superclass edge is honored only when the superclass is present in the
/// module list *and* registered in the `ClassRegistry` (production registers the whole module
/// before building the helper chain). Otherwise the class is planned as a hierarchy root with
/// an empty inherited prefix, so directly constructed fixtures never fail planning.
public final class CVtablePlanner {
    /// C expression planned for slotless instances of a hierarchy that does carry a vtable
    /// field (side branches, including a slotless root). Distinct from `Optional.empty()`,
    /// which means the whole hierarchy has no vtable field at all.
    public static final String VTABLE_NULL_SYMBOL = "NULL";

    /// One vtable slot: the identity of a polymorphic GDCC instance method, introduced by
    /// `introducerClassName`. `introducerFunction` is the signature source for the slot's C
    /// function pointer type; `coroutine` marks slots whose entries must point at coroutine
    /// start thunks instead of plain implementations (chain-wide consistent, enforced by the
    /// compatibility check).
    public record VtableSlot(
            @NotNull String methodName,
            @NotNull String introducerClassName,
            @NotNull FunctionDef introducerFunction,
            boolean coroutine
    ) {
        public VtableSlot {
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(introducerClassName, "introducerClassName");
            Objects.requireNonNull(introducerFunction, "introducerFunction");
        }
    }

    /// Per-class resolved entry for one slot: `finalOverriderClassName` /
    /// `finalOverriderFunction` provide the implementation this class's vtable instance must
    /// point at. `abstractHole` marks entries whose final overrider is an abstract declaration
    /// (planned as a NULL function pointer; only legal on abstract classes — concrete classes
    /// are rejected during planning).
    public record VtableSlotEntry(
            @NotNull VtableSlot slot,
            @NotNull String finalOverriderClassName,
            @NotNull FunctionDef finalOverriderFunction,
            boolean abstractHole
    ) {
        public VtableSlotEntry {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(finalOverriderClassName, "finalOverriderClassName");
            Objects.requireNonNull(finalOverriderFunction, "finalOverriderFunction");
        }
    }

    /// Per-class planning facts backing the read-only queries.
    private record ClassPlan(
            @Nullable String gdccParentName,
            @NotNull List<VtableSlotEntry> slotEntries,
            boolean introducesSlot,
            boolean overridesInheritedSlot,
            @NotNull String rootName,
            @NotNull Set<String> ancestorNames
    ) {
    }

    private final @NotNull Map<String, ClassPlan> planByClassName;
    /// Override edges as `class -> (method -> nearest compatible ancestor declarer)`; kept for
    /// `isPolymorphicCall` chain walks.
    private final @NotNull Map<String, Map<String, String>> overrideTargetByClass;
    private final @NotNull Set<String> rootsWithSlots;

    public CVtablePlanner(@NotNull List<? extends ClassDef> moduleClassDefs, @NotNull ClassRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        var moduleByName = new LinkedHashMap<String, ClassDef>();
        for (var classDef : moduleClassDefs) {
            moduleByName.put(classDef.getName(), classDef);
        }

        var parentByClass = resolveGdccParents(moduleByName, registry);
        var rootByClass = new HashMap<String, String>();
        var ancestorsByClass = new HashMap<String, Set<String>>();
        computeRootsAndAncestors(moduleByName, parentByClass, rootByClass, ancestorsByClass);
        var ordered = computeBaseBeforeDerivedOrder(moduleByName, parentByClass);
        var declaredByClass = collectDeclaredMethods(moduleByName);
        var overrideTargets = resolveOverrideTargets(ordered, parentByClass, declaredByClass);
        var polymorphicMethods = markPolymorphicDeclarations(overrideTargets);
        var plans = allocateSlots(ordered, moduleByName, parentByClass, rootByClass, ancestorsByClass,
                declaredByClass, overrideTargets, polymorphicMethods);

        var slottedRoots = new HashSet<String>();
        for (var plan : plans.values()) {
            if (!plan.slotEntries().isEmpty()) {
                slottedRoots.add(plan.rootName());
            }
        }
        this.planByClassName = Collections.unmodifiableMap(plans);
        var overrideCopy = new LinkedHashMap<String, Map<String, String>>();
        overrideTargets.forEach((className, targets) -> overrideCopy.put(className, Collections.unmodifiableMap(targets)));
        this.overrideTargetByClass = Collections.unmodifiableMap(overrideCopy);
        this.rootsWithSlots = Collections.unmodifiableSet(slottedRoots);
    }

    /// Ordered slot entries of one class (prefix-compatible with its GDCC parent); empty for
    /// slotless or unknown classes.
    public @NotNull List<VtableSlotEntry> slots(@NotNull String className) {
        var plan = planByClassName.get(className);
        return plan == null ? List.of() : plan.slotEntries();
    }

    /// Looks up one slot by method name in the owner's (inherited-included) slot list. A hit
    /// must NOT be used as the indirect-dispatch gate: sibling branches and final overriders
    /// carry inherited slots whose call sites stay direct — use `isPolymorphicCall` for that.
    public @NotNull Optional<VtableSlotEntry> findVtableSlot(@NotNull String ownerClassName, @NotNull String methodName) {
        for (var entry : slots(ownerClassName)) {
            if (entry.slot().methodName().equals(methodName)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /// Call-site predicate: a resolved call on a receiver statically typed `receiverTypeName`
    /// needs vtable-indirect dispatch exactly when a proper in-module descendant of that type
    /// overrides the method. The walk follows override edges upward from each descendant
    /// declaration; a chain broken by an excluded (static/hidden/lambda/`_init`) declaration
    /// below the receiver type does not count.
    public boolean isPolymorphicCall(@NotNull String receiverTypeName, @NotNull String methodName) {
        var receiverPlan = planByClassName.get(receiverTypeName);
        if (receiverPlan == null) {
            return false;
        }
        for (var candidate : planByClassName.entrySet()) {
            var candidateName = candidate.getKey();
            if (candidateName.equals(receiverTypeName) || !candidate.getValue().ancestorNames().contains(receiverTypeName)) {
                continue;
            }
            var target = overrideTargetByClass.getOrDefault(candidateName, Map.of()).get(methodName);
            if (target == null) {
                continue;
            }
            // Ascend the override chain: reaching the receiver type itself, or any ancestor of
            // it, means this descendant overrides the method as seen from the receiver type.
            var current = target;
            while (true) {
                if (current.equals(receiverTypeName) || receiverPlan.ancestorNames().contains(current)) {
                    return true;
                }
                var next = overrideTargetByClass.getOrDefault(current, Map.of()).get(methodName);
                if (next == null) {
                    break;
                }
                current = next;
            }
        }
        return false;
    }

    public boolean slotted(@NotNull String className) {
        return !slots(className).isEmpty();
    }

    public boolean introducesSlot(@NotNull String className) {
        var plan = planByClassName.get(className);
        return plan != null && plan.introducesSlot();
    }

    public boolean overridesInheritedSlot(@NotNull String className) {
        var plan = planByClassName.get(className);
        return plan != null && plan.overridesInheritedSlot();
    }

    public boolean passThrough(@NotNull String className) {
        return slotted(className) && !introducesSlot(className) && !overridesInheritedSlot(className);
    }

    /// Vtable struct typedef this class's instances point at: the class's own typedef when it
    /// introduces slots, otherwise the nearest introducer ancestor's typedef; empty when the
    /// class carries no slots. Names use the raw canonical class name (the same naming layer
    /// as `struct <Class>` and `<Class>_object_ptr`, not `cIdentifier()` output).
    public @NotNull Optional<String> vtableInstanceType(@NotNull String className) {
        if (!slotted(className)) {
            return Optional.empty();
        }
        var current = className;
        while (true) {
            if (introducesSlot(current)) {
                return Optional.of(renderVtableTypeName(current));
            }
            // A slotted class always reaches an introducer along intact parent edges.
            current = requireGdccParent(current, "vtable instance type resolution");
        }
    }

    /// C expression this class's `_vtable` field must be initialized with: the class's own
    /// `gdcc_<C>_vtable_inst` when it introduces slots or overrides inherited ones; the nearest
    /// non-pass-through ancestor's instance symbol for pass-through classes (that ancestor may
    /// merely override, it need not introduce); `NULL` for side branches of a slotted
    /// hierarchy; empty when the whole hierarchy is slotless (no `_vtable` field exists).
    public @NotNull Optional<String> resolvedVtableSymbol(@NotNull String className) {
        var plan = planByClassName.get(className);
        if (plan == null || !rootsWithSlots.contains(plan.rootName())) {
            return Optional.empty();
        }
        if (!slotted(className)) {
            return Optional.of(VTABLE_NULL_SYMBOL);
        }
        var current = className;
        while (true) {
            if (introducesSlot(current) || overridesInheritedSlot(current)) {
                return Optional.of(renderVtableInstanceSymbol(current));
            }
            // Pass-through classes always reach a non-pass-through ancestor before the chain ends.
            current = requireGdccParent(current, "vtable symbol resolution");
        }
    }

    /// Strict GDCC↔GDCC override compatibility: both sides must be non-static, non-hidden,
    /// non-lambda instance methods with identical vararg shape and coroutine markers, and —
    /// after dropping each side's synthetic leading `self` parameter independently — identical
    /// parameter counts, parameter types and return types. Comparison uses strict type
    /// equality, never assignability. Callers filter excluded methods (`_init`, static,
    /// hidden, lambda) before consulting this entry.
    public static boolean checkGdccOverrideSignature(
            @NotNull FunctionDef baseFunction,
            @NotNull FunctionDef overrideFunction
    ) {
        Objects.requireNonNull(baseFunction, "baseFunction");
        Objects.requireNonNull(overrideFunction, "overrideFunction");
        if (baseFunction.isStatic() || overrideFunction.isStatic()) {
            return false;
        }
        if (baseFunction.isHidden() || overrideFunction.isHidden()) {
            return false;
        }
        if (baseFunction.isLambda() || overrideFunction.isLambda()) {
            return false;
        }
        if (baseFunction.isVararg() != overrideFunction.isVararg()) {
            return false;
        }
        if (isCoroutine(baseFunction) != isCoroutine(overrideFunction)) {
            return false;
        }
        var baseStart = leadingSelfWidth(baseFunction);
        var overrideStart = leadingSelfWidth(overrideFunction);
        var baseCount = baseFunction.getParameterCount() - baseStart;
        var overrideCount = overrideFunction.getParameterCount() - overrideStart;
        if (baseCount != overrideCount) {
            return false;
        }
        for (var index = 0; index < baseCount; index++) {
            var baseParameter = baseFunction.getParameter(index + baseStart);
            var overrideParameter = overrideFunction.getParameter(index + overrideStart);
            if (baseParameter == null || overrideParameter == null) {
                return false;
            }
            if (!Objects.equals(baseParameter.getType(), overrideParameter.getType())) {
                return false;
            }
        }
        return Objects.equals(baseFunction.getReturnType(), overrideFunction.getReturnType());
    }

    /// A GDCC superclass edge exists only when the superclass is both in the module list and
    /// registered as a GDCC class; anything else (engine/native parent, unregistered or absent
    /// superclass) makes the class a hierarchy root under the tolerance rule.
    private static @NotNull Map<String, String> resolveGdccParents(
            @NotNull Map<String, ClassDef> moduleByName,
            @NotNull ClassRegistry registry
    ) {
        var parentByClass = new HashMap<String, String>();
        for (var classDef : moduleByName.values()) {
            var superName = classDef.getSuperName();
            if (!superName.isEmpty() && moduleByName.containsKey(superName) && registry.isGdccClass(superName)) {
                parentByClass.put(classDef.getName(), superName);
            }
        }
        return parentByClass;
    }

    private static void computeRootsAndAncestors(
            @NotNull Map<String, ClassDef> moduleByName,
            @NotNull Map<String, String> parentByClass,
            @NotNull Map<String, String> rootByClassOut,
            @NotNull Map<String, Set<String>> ancestorsByClassOut
    ) {
        for (var className : moduleByName.keySet()) {
            var ancestors = new LinkedHashSet<String>();
            var root = className;
            var current = parentByClass.get(className);
            while (current != null) {
                if (!ancestors.add(current)) {
                    throw new IllegalStateException(
                            "Detected GDCC inheritance cycle involving class " + className);
                }
                root = current;
                current = parentByClass.get(current);
            }
            rootByClassOut.put(className, root);
            ancestorsByClassOut.put(className, Collections.unmodifiableSet(ancestors));
        }
    }

    /// Deterministic base-before-derived order over the module's classes: a class never
    /// precedes its in-module GDCC superclass, and same-ready classes keep module order. The
    /// cycle guard is defensive; `computeRootsAndAncestors` already rejected cycles.
    private static @NotNull List<String> computeBaseBeforeDerivedOrder(
            @NotNull Map<String, ClassDef> moduleByName,
            @NotNull Map<String, String> parentByClass
    ) {
        var ordered = new ArrayList<String>(moduleByName.size());
        var emitted = new HashSet<String>();
        var remaining = new ArrayList<>(moduleByName.keySet());
        while (!remaining.isEmpty()) {
            var progressed = false;
            for (var iterator = remaining.iterator(); iterator.hasNext(); ) {
                var candidate = iterator.next();
                var parent = parentByClass.get(candidate);
                if (parent == null || emitted.contains(parent)) {
                    ordered.add(candidate);
                    emitted.add(candidate);
                    iterator.remove();
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException("Inheritance cycle among module classes: " + remaining);
            }
        }
        return ordered;
    }

    /// Declared methods per class keyed by name, preserving declaration order; the first
    /// declaration wins on duplicates (the frontend already guarantees uniqueness).
    private static @NotNull Map<String, LinkedHashMap<String, FunctionDef>> collectDeclaredMethods(
            @NotNull Map<String, ClassDef> moduleByName
    ) {
        var declaredByClass = new LinkedHashMap<String, LinkedHashMap<String, FunctionDef>>();
        for (var classDef : moduleByName.values()) {
            var declared = new LinkedHashMap<String, FunctionDef>();
            for (var function : classDef.getFunctions()) {
                declared.putIfAbsent(function.getName(), function);
            }
            declaredByClass.put(classDef.getName(), declared);
        }
        return declaredByClass;
    }

    /// Resolves, for every declared non-excluded instance method, the nearest ancestor
    /// declaration of the same name (any kind). An excluded nearest declaration (static,
    /// hidden, lambda, `_init`) breaks the override chain: the descendant method is fresh, not
    /// an override, and no conflict is raised. A non-excluded nearest declaration must be
    /// strictly signature-compatible, otherwise planning fails fast.
    private static @NotNull Map<String, Map<String, String>> resolveOverrideTargets(
            @NotNull List<String> ordered,
            @NotNull Map<String, String> parentByClass,
            @NotNull Map<String, LinkedHashMap<String, FunctionDef>> declaredByClass
    ) {
        var overrideTargetByClass = new LinkedHashMap<String, Map<String, String>>();
        for (var className : ordered) {
            for (var declared : declaredByClass.get(className).entrySet()) {
                var methodName = declared.getKey();
                var function = declared.getValue();
                if (isExcludedFromVtable(function)) {
                    continue;
                }
                String nearestDeclarersName = null;
                FunctionDef nearestDeclaration = null;
                var ancestorName = parentByClass.get(className);
                while (ancestorName != null) {
                    var ancestorDeclaration = declaredByClass.get(ancestorName).get(methodName);
                    if (ancestorDeclaration != null) {
                        nearestDeclarersName = ancestorName;
                        nearestDeclaration = ancestorDeclaration;
                        break;
                    }
                    ancestorName = parentByClass.get(ancestorName);
                }
                if (nearestDeclaration == null || isExcludedFromVtable(nearestDeclaration)) {
                    continue;
                }
                if (!checkGdccOverrideSignature(nearestDeclaration, function)) {
                    throw new CodegenException(
                            "Method '" + methodName + "' declared in GDCC class '" + className
                                    + "' is incompatible with the same-named method in ancestor class '"
                                    + nearestDeclarersName + "' (static/vararg/coroutine markers, parameter "
                                    + "and return types must match exactly)");
                }
                overrideTargetByClass
                        .computeIfAbsent(className, _ -> new LinkedHashMap<>())
                        .put(methodName, nearestDeclarersName);
            }
        }
        return overrideTargetByClass;
    }

    /// Marks every declaration that has at least one overrider below it by following each
    /// override edge up to the topmost declarer; marked declarations are exactly the
    /// polymorphic ones.
    private static @NotNull Map<String, Set<String>> markPolymorphicDeclarations(
            @NotNull Map<String, Map<String, String>> overrideTargetByClass
    ) {
        var polymorphicMethods = new HashMap<String, Set<String>>();
        for (var classEntry : overrideTargetByClass.entrySet()) {
            for (var methodEntry : classEntry.getValue().entrySet()) {
                var methodName = methodEntry.getKey();
                var target = methodEntry.getValue();
                while (target != null) {
                    polymorphicMethods.computeIfAbsent(target, _ -> new HashSet<>()).add(methodName);
                    target = overrideTargetByClass.getOrDefault(target, Map.of()).get(methodName);
                }
            }
        }
        return polymorphicMethods;
    }

    /// Slot allocation in base-before-derived order: inherited slots prefix the list,
    /// compatible overrides reuse them, and polymorphic fresh declarations append new slots.
    /// Afterwards each slot resolves its final overrider (nearest declaration from the class
    /// up to the introducer) and the abstract-hole rule is enforced.
    private static @NotNull LinkedHashMap<String, ClassPlan> allocateSlots(
            @NotNull List<String> ordered,
            @NotNull Map<String, ClassDef> moduleByName,
            @NotNull Map<String, String> parentByClass,
            @NotNull Map<String, String> rootByClass,
            @NotNull Map<String, Set<String>> ancestorsByClass,
            @NotNull Map<String, LinkedHashMap<String, FunctionDef>> declaredByClass,
            @NotNull Map<String, Map<String, String>> overrideTargetByClass,
            @NotNull Map<String, Set<String>> polymorphicMethods
    ) {
        var plans = new LinkedHashMap<String, ClassPlan>();
        var slotsByClass = new HashMap<String, List<VtableSlot>>();
        for (var className : ordered) {
            var parent = parentByClass.get(className);
            var inheritedSlots = parent == null ? List.<VtableSlot>of() : slotsByClass.get(parent);
            var slots = new ArrayList<>(inheritedSlots);
            var slotByName = new HashMap<String, VtableSlot>();
            for (var slot : inheritedSlots) {
                slotByName.put(slot.methodName(), slot);
            }

            var introduces = false;
            var overrides = false;
            for (var declared : declaredByClass.get(className).entrySet()) {
                var methodName = declared.getKey();
                var function = declared.getValue();
                if (isExcludedFromVtable(function)) {
                    continue;
                }
                var overrideTarget = overrideTargetByClass.getOrDefault(className, Map.of()).get(methodName);
                if (overrideTarget != null) {
                    if (!slotByName.containsKey(methodName)) {
                        throw new IllegalStateException(
                                "Override of '" + overrideTarget + "." + methodName + "' from class '" + className
                                        + "' found no inherited slot to reuse");
                    }
                    overrides = true;
                } else if (polymorphicMethods.getOrDefault(className, Set.of()).contains(methodName)) {
                    if (slotByName.containsKey(methodName)) {
                        // Reachable only through an excluded declaration that broke the override
                        // chain while an inherited same-named slot survives above it; two
                        // same-named struct fields cannot coexist in one vtable.
                        throw new CodegenException(
                                "Method '" + methodName + "' declared in GDCC class '" + className
                                        + "' cannot introduce a vtable slot: an inherited slot with the same "
                                        + "name already exists behind an excluded (static/hidden/lambda/_init) "
                                        + "declaration; rename one of the declarations");
                    }
                    var slot = new VtableSlot(methodName, className, function, isCoroutine(function));
                    slots.add(slot);
                    slotByName.put(methodName, slot);
                    introduces = true;
                }
            }
            slotsByClass.put(className, List.copyOf(slots));

            var slotEntries = new ArrayList<VtableSlotEntry>(slots.size());
            for (var slot : slots) {
                slotEntries.add(resolveSlotEntry(className, moduleByName.get(className), parentByClass, declaredByClass, slot));
            }
            plans.put(className, new ClassPlan(
                    parent,
                    List.copyOf(slotEntries),
                    introduces,
                    overrides,
                    rootByClass.get(className),
                    ancestorsByClass.get(className)
            ));
        }
        return plans;
    }

    /// The final overrider of one slot for one class: the nearest non-excluded declaration
    /// walking from the class up to (and including) the introducer. Excluded declarations are
    /// skipped — a static/hidden/lambda/`_init` namesake never intercepts instance dispatch.
    /// The introducer itself always declares the method, so the walk cannot overshoot it.
    private static @NotNull VtableSlotEntry resolveSlotEntry(
            @NotNull String className,
            @NotNull ClassDef classDef,
            @NotNull Map<String, String> parentByClass,
            @NotNull Map<String, LinkedHashMap<String, FunctionDef>> declaredByClass,
            @NotNull VtableSlot slot
    ) {
        String overriderClass = null;
        FunctionDef overriderFunction = null;
        var current = className;
        while (current != null) {
            var declared = declaredByClass.get(current).get(slot.methodName());
            if (declared != null && !isExcludedFromVtable(declared)) {
                overriderClass = current;
                overriderFunction = declared;
                break;
            }
            current = parentByClass.get(current);
        }
        if (overriderFunction == null) {
            throw new IllegalStateException(
                    "Vtable slot '" + slot.methodName() + "' of class '" + className + "' has no declaring class");
        }
        var abstractHole = overriderFunction.isAbstract();
        if (abstractHole && !classDef.isAbstract()) {
            throw new CodegenException(
                    "Concrete GDCC class '" + className + "' inherits vtable slot '" + slot.methodName()
                            + "' (introduced by class '" + slot.introducerClassName()
                            + "') without any concrete implementation");
        }
        return new VtableSlotEntry(slot, overriderClass, overriderFunction, abstractHole);
    }

    /// Excluded from slots, polymorphic checks and conflict checks: `_init` pairs are
    /// constructor semantics whose parent/child signatures routinely differ, and static,
    /// hidden or lambda methods never participate in instance dispatch.
    private static boolean isExcludedFromVtable(@NotNull FunctionDef function) {
        return function.isStatic()
                || function.isHidden()
                || function.isLambda()
                || "_init".equals(function.getName());
    }

    /// The coroutine marker only exists on LIR functions; other `FunctionDef` implementations
    /// cannot be coroutines in the backend (same convention as `CGenHelper`'s coroutine scan).
    private static boolean isCoroutine(@NotNull FunctionDef function) {
        return function instanceof LirFunctionDef lirFunction && lirFunction.isCoroutine();
    }

    /// The synthetic `self` parameter only ever sits at index 0 (frontend lowering injects it
    /// there for instance methods); it is skipped per side so two instance methods compare
    /// their user-declared parameter lists.
    private static int leadingSelfWidth(@NotNull FunctionDef function) {
        if (function.getParameterCount() == 0) {
            return 0;
        }
        var leading = function.getParameter(0);
        return leading != null && leading.getName().equals("self") ? 1 : 0;
    }

    private @NotNull String requireGdccParent(@NotNull String className, @NotNull String surface) {
        var parent = planByClassName.get(className).gdccParentName();
        if (parent == null) {
            throw new IllegalStateException(
                    "Class '" + className + "' has no GDCC parent while resolving " + surface);
        }
        return parent;
    }

    private static @NotNull String renderVtableTypeName(@NotNull String className) {
        return "gdcc_" + className + "_vtable";
    }

    private static @NotNull String renderVtableInstanceSymbol(@NotNull String className) {
        return "gdcc_" + className + "_vtable_inst";
    }
}
