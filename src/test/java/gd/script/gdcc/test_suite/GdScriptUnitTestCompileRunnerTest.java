package gd.script.gdcc.test_suite;

import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static java.time.Duration.ofNanos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class GdScriptUnitTestCompileRunnerTest {
    private static final String TIMING_ENV = "GDCC_TEST_TIMING";
    private static final List<String> EXPECTED_SCRIPT_PATHS = List.of(
            "abi/array/plain_surface_roundtrip.gd",
            "abi/typed_array/array_leaf_return_roundtrip.gd",
            "abi/typed_array/builtin_method_wrong_typed_guard.gd",
            "abi/typed_array/gdcc_inner_object_roundtrip.gd",
            "abi/typed_array/method_exact_roundtrip.gd",
            "abi/typed_array/method_plain_guard.gd",
            "abi/typed_array/method_wrong_typed_guard.gd",
            "abi/typed_array/property_plain_guard.gd",
            "abi/typed_array/property_roundtrip.gd",
            "abi/typed_array/property_wrong_typed_guard.gd",
            "abi/typed_array/return_roundtrip.gd",
            "abi/typed_dictionary/gdcc_inner_object_roundtrip.gd",
            "abi/typed_dictionary/method_exact_roundtrip.gd",
            "abi/typed_dictionary/method_plain_guard.gd",
            "abi/typed_dictionary/method_wrong_typed_guard.gd",
            "abi/typed_dictionary/property_plain_guard.gd",
            "abi/typed_dictionary/property_roundtrip.gd",
            "abi/typed_dictionary/property_wrong_typed_guard.gd",
            "abi/typed_dictionary/return_roundtrip.gd",
            "abi/variant/method_roundtrip.gd",
            "abi/variant/non_variant_guard.gd",
            "abi/variant/property_roundtrip.gd",
            "algorithm/fibonacci_sequence.gd",
            "algorithm/graph_traversal.gd",
            "annotation/export_object_hints.gd",
            "annotation/export_variant_hints.gd",
            "annotation/tool_process_runtime.gd",
            "cast/builtin_identity_conversion.gd",
            "cast/cast_result_consumers.gd",
            "cast/engine_object_cast.gd",
            "cast/gdcc_object_cast.gd",
            "cast/null_freed_object_cast.gd",
            "cast/parameterized_container_cast.gd",
            "cast/variant_to_builtin_runtime_failure.gd",
            "cast/variant_to_builtin_success.gd",
            "collection/array_literal_roundtrip.gd",
            "collection/array_sum_and_mutation.gd",
            "collection/container_literal_evaluation_order.gd",
            "collection/container_literal_nested_untyped.gd",
            "collection/dictionary_literal_roundtrip.gd",
            "collection/dictionary_mutation_and_lookup.gd",
            "collection/for_in_container_literal.gd",
            "collection/typed_container_literal_boundaries.gd",
            "constructor/atomic_builtin_constructor_roundtrip.gd",
            "constructor/builtin_variant_container_roundtrip.gd",
            "constructor/builtin_variant_scalar_roundtrip.gd",
            "constructor/int_to_float_builtin_constructor.gd",
            "control_flow/for_break_continue.gd",
            "control_flow/for_generic_variant_loop.gd",
            "control_flow/for_known_iterable_loop.gd",
            "control_flow/for_range_loop.gd",
            "control_flow/if_elif_truthiness.gd",
            "control_flow/match_array_destructure.gd",
            "control_flow/match_bind_guard.gd",
            "control_flow/match_control_flow_mix.gd",
            "control_flow/match_dict_destructure.gd",
            "control_flow/match_expression.gd",
            "control_flow/match_lambda.gd",
            "control_flow/match_literal_wildcard.gd",
            "control_flow/match_mixed.gd",
            "control_flow/match_nested_containers.gd",
            "control_flow/match_string_stringname.gd",
            "control_flow/nested_for_while_break_continue.gd",
            "control_flow/recursive_factorial.gd",
            "control_flow/while_break_continue.gd",
            "coroutine/await_call_immediate.gd",
            "coroutine/await_call_suspend.gd",
            "coroutine/await_dynamic_late.gd",
            "coroutine/await_dynamic_signal.gd",
            "coroutine/await_emitter_release.gd",
            "coroutine/await_engine_signal.gd",
            "coroutine/await_fire_and_forget.gd",
            "coroutine/await_interop_interpreted.gd",
            "coroutine/await_loop.gd",
            "coroutine/await_recursive.gd",
            "coroutine/await_signal_args.gd",
            "coroutine/await_signal_basic.gd",
            "coroutine/await_signal_connect_failure.gd",
            "coroutine/await_signal_nested.gd",
            "coroutine/await_typed_engine_boundary.gd",
            "coroutine/interop_state_completed_signal.gd",
            "coroutine/static_await_called_from_godot.gd",
            "coroutine/static_await_chain.gd",
            "coroutine/static_await_fire_and_forget.gd",
            "coroutine/static_await_lambda_interop.gd",
            "coroutine/static_await_signal.gd",
            "coroutine/static_await_typed_result.gd",
            "default_args/dynamic_partial_fill.gd",
            "default_args/engine_virtual_process_default.gd",
            "default_args/internal_exact_and_static.gd",
            "default_args/per_call_reevaluation.gd",
            "default_args/too_few_arguments_negative.gd",
            "default_args/too_many_arguments_negative.gd",
            "initializer/local/arithmetic_chain.gd",
            "initializer/local/constructors_and_constants.gd",
            "initializer/local/int_to_float_boundaries.gd",
            "initializer/local/object_and_engine_constructor.gd",
            "initializer/local/string_to_stringname_boundaries.gd",
            "initializer/local/stringname_to_string_boundaries.gd",
            "initializer/local/variant_boundaries.gd",
            "initializer/local/vectori_to_vector_boundaries.gd",
            "initializer/property/int_to_float_boundaries.gd",
            "initializer/property/object_and_scalar.gd",
            "lambda/captures.gd",
            "lambda/control_flow_bodies.gd",
            "lambda/lambda_await_awaited_by_named.gd",
            "lambda/lambda_await_capture.gd",
            "lambda/lambda_await_capture_release_balance.gd",
            "lambda/lambda_await_capture_write.gd",
            "lambda/lambda_await_concurrent_calls.gd",
            "lambda/lambda_await_construct_after_resume.gd",
            "lambda/lambda_await_done_fast_path.gd",
            "lambda/lambda_await_fire_and_forget_inner.gd",
            "lambda/lambda_await_named_coroutine_chain.gd",
            "lambda/lambda_await_nested.gd",
            "lambda/lambda_await_released_callable.gd",
            "lambda/lambda_await_self_capture.gd",
            "lambda/lambda_await_signal_connect_callback.gd",
            "lambda/lambda_await_spawned_by_named.gd",
            "lambda/lambda_await_suspend_path.gd",
            "lambda/self_nested_return.gd",
            "lambda/signal_and_engine.gd",
            "lambda/value_call_and_arity.gd",
            "member/builtin_property_access.gd",
            "member/builtin_property_writeback_color.gd",
            "member/builtin_property_writeback_vector3.gd",
            "member/callable_value_refs.gd",
            "member/compound_assignment.gd",
            "member/signal_connect_lambda.gd",
            "member/signal_emit_connect.gd",
            "member/signal_inherited_and_engine.gd",
            "member/signal_interop_bidirectional.gd",
            "member/signal_interop_compiled_to_interpreted.gd",
            "member/signal_interop_engine_crossing.gd",
            "member/signal_interop_interpreted_to_compiled.gd",
            "member/signal_null_receiver.gd",
            "member/signal_value_read.gd",
            "member/static_var_basic.gd",
            "member/static_var_destroyable.gd",
            "member/static_var_inheritance.gd",
            "member/static_var_instance_access.gd",
            "runtime/array_constructor_size.gd",
            "runtime/array_void_return_helper_size.gd",
            "runtime/array_void_return_push_back_size.gd",
            "runtime/builtin_color_from_hsv_static_call.gd",
            "runtime/comment_statement_control_flow_surface.gd",
            "runtime/dual_role_singleton_mixed_use_sites.gd",
            "runtime/dual_role_singleton_static_constant.gd",
            "runtime/dynamic_call.gd",
            "runtime/dynamic_member_variant_named_access.gd",
            "runtime/dynamic_member_variant_named_access_missing.gd",
            "runtime/dynamic_member_variant_signal_read.gd",
            "runtime/engine_array_mesh_exact_default_args.gd",
            "runtime/engine_json_parse_string_static_call.gd",
            "runtime/engine_node_add_child_exact_explicit_internal_args.gd",
            "runtime/engine_node_add_child_exact_typed_receiver.gd",
            "runtime/engine_node_call_exact_vararg_discard_return.gd",
            "runtime/engine_node_call_exact_vararg_error_path.gd",
            "runtime/engine_node_call_exact_vararg_success.gd",
            "runtime/engine_node_refcounted_workflow.gd",
            "runtime/engine_option_button_default_args.gd",
            "runtime/engine_scene_tree_call_group_flags_exact_vararg.gd",
            "runtime/inherited_engine_static_constant.gd",
            "runtime/int_to_float_engine_class.gd",
            "runtime/int_to_float_inbound_dynamic_call.gd",
            "runtime/rect2i_to_rect2_call_guard.gd",
            "runtime/singleton_receiver_binding_drift.gd",
            "runtime/singleton_receiver_calls.gd",
            "runtime/string_literal_escape_unicode_surface.gd",
            "runtime/string_literal_internal_surface.gd",
            "runtime/string_literal_utf8_offset_surface.gd",
            "runtime/string_stringname_inbound_dynamic_call.gd",
            "runtime/vectori_to_vector_inbound_dynamic_call.gd",
            "runtime/vectori_to_vector_reverse_guard.gd",
            "runtime/virtual/physics_process_called_and_delta_valid.gd",
            "runtime/virtual/process_called_and_delta_valid.gd",
            "runtime/virtual/ready_called_once.gd",
            "runtime/virtual/ready_parent_chain_dispatch.gd",
            "scene/get_node_control_flow_scene.gd",
            "scene/get_node_lambda_await_scene.gd",
            "scene/get_node_lambda_flow_scene.gd",
            "scene/get_node_shorthand_scene.gd",
            "scene/nested_node_refcounted_scene.gd",
            "smoke/basic_arithmetic.gd",
            "smoke/not_in_membership.gd",
            "smoke/object_identity_equality.gd",
            "smoke/object_nil_equality.gd",
            "string_format/array_multi_placeholder.gd",
            "string_format/compound_assignment.gd",
            "string_format/numeric_family.gd",
            "string_format/percent_escape.gd",
            "string_format/runtime_error_arg_count.gd",
            "string_format/single_value.gd",
            "string_format/typed_array_operand.gd",
            "string_format/variant_operand.gd",
            "subscript/array_roundtrip.gd",
            "subscript/dictionary_float_key_roundtrip.gd",
            "subscript/packed_array_mutation_roundtrip.gd",
            "subscript/string_stringname_dictionary_key_roundtrip.gd",
            "ternary/basic_same_type.gd",
            "ternary/condition_context.gd",
            "ternary/destroyable_arms.gd",
            "ternary/mixed_int_float.gd",
            "ternary/nested_associativity.gd",
            "ternary/non_bool_condition.gd",
            "ternary/null_arm.gd",
            "ternary/object_ancestor_merge.gd",
            "ternary/statement_position_discard.gd",
            "type_test/builtin_type_test.gd",
            "type_test/container_type_test.gd",
            "type_test/is_not_test.gd",
            "type_test/object_type_test.gd",
            "type_test/packed_type_test.gd",
            "type_test/variant_type_test.gd"
    );
    private static final List<String> ABI_SCRIPT_PATHS = scriptPathsWithPrefix("abi/");
    private static final List<String> ALGORITHM_SCRIPT_PATHS = scriptPathsWithPrefix("algorithm/");
    private static final List<String> ANNOTATION_SCRIPT_PATHS = scriptPathsWithPrefix("annotation/");
    private static final List<String> CAST_SCRIPT_PATHS = scriptPathsWithPrefix("cast/");
    private static final List<String> COLLECTION_SCRIPT_PATHS = scriptPathsWithPrefix("collection/");
    private static final List<String> CONSTRUCTOR_SCRIPT_PATHS = scriptPathsWithPrefix("constructor/");
    private static final List<String> CONTROL_FLOW_SCRIPT_PATHS = scriptPathsWithPrefix("control_flow/");
    private static final List<String> COROUTINE_SCRIPT_PATHS = scriptPathsWithPrefix("coroutine/");
    private static final List<String> DEFAULT_ARGS_SCRIPT_PATHS = scriptPathsWithPrefix("default_args/");
    private static final List<String> INITIALIZER_SCRIPT_PATHS = scriptPathsWithPrefix("initializer/");
    private static final List<String> LAMBDA_SCRIPT_PATHS = scriptPathsWithPrefix("lambda/");
    private static final List<String> MEMBER_SCRIPT_PATHS = scriptPathsWithPrefix("member/");
    private static final List<String> RUNTIME_SCRIPT_PATHS = scriptPathsWithPrefix("runtime/");
    private static final List<String> SCENE_SCRIPT_PATHS = scriptPathsWithPrefix("scene/");
    private static final List<String> SMOKE_SCRIPT_PATHS = scriptPathsWithPrefix("smoke/");
    private static final List<String> STRING_FORMAT_SCRIPT_PATHS = scriptPathsWithPrefix("string_format/");
    private static final List<String> SUBSCRIPT_SCRIPT_PATHS = scriptPathsWithPrefix("subscript/");
    private static final List<String> TERNARY_SCRIPT_PATHS = scriptPathsWithPrefix("ternary/");
    private static final List<String> TYPE_TEST_SCRIPT_PATHS = scriptPathsWithPrefix("type_test/");
    private static final int PHYSICS_FRAME_QUIT_AFTER_FRAMES = 60;
    private static final Set<String> PHYSICS_FRAME_SCRIPT_PATHS = Set.of(
            "runtime/virtual/physics_process_called_and_delta_valid.gd"
    );

    @Test
    void listsExpectedBundledUnitScripts() throws Exception {
        var runner = new GdScriptUnitTestCompileRunner();
        var scriptPaths = runner.listScriptResourcePaths();
        assertFalse(scriptPaths.isEmpty(), "Expected at least one bundled unit-test case");
        assertEquals(
                EXPECTED_SCRIPT_PATHS,
                scriptPaths,
                () -> "Unexpected bundled unit-test script set: " + scriptPaths
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesAbiScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                ABI_SCRIPT_PATHS,
                "Zig not found; skipping ABI GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesCastScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                CAST_SCRIPT_PATHS,
                "Zig not found; skipping cast GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesAlgorithmScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                ALGORITHM_SCRIPT_PATHS,
                "Zig not found; skipping algorithm GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesAnnotationScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                ANNOTATION_SCRIPT_PATHS,
                "Zig not found; skipping annotation GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesCollectionScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                COLLECTION_SCRIPT_PATHS,
                "Zig not found; skipping collection GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesConstructorScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                CONSTRUCTOR_SCRIPT_PATHS,
                "Zig not found; skipping constructor GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesControlFlowScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                CONTROL_FLOW_SCRIPT_PATHS,
                "Zig not found; skipping control-flow GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesCoroutineScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                COROUTINE_SCRIPT_PATHS,
                "Zig not found; skipping coroutine GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesDefaultArgsScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                DEFAULT_ARGS_SCRIPT_PATHS,
                "Zig not found; skipping default-argument GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesInitializerScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                INITIALIZER_SCRIPT_PATHS,
                "Zig not found; skipping initializer GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesLambdaScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                LAMBDA_SCRIPT_PATHS,
                "Zig not found; skipping lambda GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesMemberScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                MEMBER_SCRIPT_PATHS,
                "Zig not found; skipping member GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesRuntimeScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                RUNTIME_SCRIPT_PATHS,
                "Zig not found; skipping runtime GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesSceneScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                SCENE_SCRIPT_PATHS,
                "Zig not found; skipping scene GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesSmokeScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                SMOKE_SCRIPT_PATHS,
                "Zig not found; skipping smoke GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesStringFormatScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                STRING_FORMAT_SCRIPT_PATHS,
                "Zig not found; skipping string_format GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesSubscriptScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                SUBSCRIPT_SCRIPT_PATHS,
                "Zig not found; skipping subscript GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesTernaryScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                TERNARY_SCRIPT_PATHS,
                "Zig not found; skipping ternary GDScript compile-run tests"
        );
    }

    @TestFactory
    Stream<DynamicTest> compilesAndValidatesTypeTestScripts() throws Exception {
        return compileAndValidateBundledUnitScripts(
                TYPE_TEST_SCRIPT_PATHS,
                "Zig not found; skipping type-test GDScript compile-run tests"
        );
    }

    private static Stream<DynamicTest> compileAndValidateBundledUnitScripts(
            List<String> scriptPaths,
            String skipMessage
    ) throws Exception {
        var timingEnabled = timingEnabled();
        var zigLookupStart = System.nanoTime();
        var zig = ZigUtil.findZig();
        var zigLookupDuration = elapsedSince(zigLookupStart);
        Assumptions.assumeTrue(zig != null, skipMessage);

        var runner = new GdScriptUnitTestCompileRunner();
        var resourceDiscoveryStart = System.nanoTime();
        var discoveredScriptPaths = runner.listScriptResourcePaths();
        var resourceDiscoveryDuration = elapsedSince(resourceDiscoveryStart);
        var resourceSetValidationStart = System.nanoTime();
        assertFalse(discoveredScriptPaths.isEmpty(), "Expected at least one bundled unit-test case");
        assertEquals(
                EXPECTED_SCRIPT_PATHS,
                discoveredScriptPaths,
                () -> "Unexpected bundled unit-test script set: " + discoveredScriptPaths
        );
        var resourceSetValidationDuration = elapsedSince(resourceSetValidationStart);
        if (timingEnabled) {
            System.out.println("[gdcc-test-timing] factory scripts=" + scriptPaths.size()
                    + " zig.lookup=" + formatDuration(zigLookupDuration)
                    + " resources.discover=" + formatDuration(resourceDiscoveryDuration)
                    + " resources.validate_set=" + formatDuration(resourceSetValidationDuration));
        }

        return scriptPaths.stream()
                .map(scriptResourcePath -> DynamicTest.dynamicTest(
                        scriptResourcePath,
                        () -> {
                            var result = new GdScriptUnitTestCompileRunner().compileAndValidate(
                                    scriptResourcePath,
                                    runOptionsFor(scriptResourcePath)
                            );
                            if (timingEnabled) {
                                System.out.println(result.timing().summaryLine(scriptResourcePath));
                            }
                        }
                ));
    }

    private static GodotGdextensionTestRunner.RunOptions runOptionsFor(String scriptResourcePath) {
        var runOptions = GodotGdextensionTestRunner.defaultRunOptions(true);
        if (PHYSICS_FRAME_SCRIPT_PATHS.contains(scriptResourcePath)) {
            return runOptions.withQuitAfterFrames(PHYSICS_FRAME_QUIT_AFTER_FRAMES);
        }
        return runOptions;
    }

    private static List<String> scriptPathsWithPrefix(String prefix) {
        return EXPECTED_SCRIPT_PATHS.stream()
                .filter(scriptPath -> scriptPath.startsWith(prefix))
                .toList();
    }

    private static Duration elapsedSince(long startNanos) {
        return ofNanos(System.nanoTime() - startNanos);
    }

    private static String formatDuration(Duration duration) {
        return String.format(Locale.ROOT, "%.3fms", duration.toNanos() / 1_000_000.0);
    }

    private static boolean timingEnabled() {
        var value = System.getenv(TIMING_ENV);
        return value != null && Set.of("1", "true", "yes", "on").contains(value.toLowerCase(Locale.ROOT));
    }
}
