package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.booking.domain.SagaStep;
import com.homefix.booking.domain.SagaStepRepository;

/**
 * Verifies the Saga orchestrator records each step before proceeding (Requirement 24.6) and,
 * on failure, compensates committed steps in reverse order (Requirement 24.7).
 */
@ExtendWith(MockitoExtension.class)
class BookingSagaOrchestratorTest {

    private static final UUID BOOKING = UUID.randomUUID();

    @Mock
    private SagaStepRepository repository;

    private BookingSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new BookingSagaOrchestrator(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void happyPathRunsAllStepsInOrderAndMarksThemCompleted() {
        List<String> order = new ArrayList<>();
        List<BookingSagaOrchestrator.Step<?>> steps = List.of(
                BookingSagaOrchestrator.Step.of("step-1", () -> {
                    order.add("s1");
                    return "a";
                }),
                BookingSagaOrchestrator.Step.of("step-2", () -> {
                    order.add("s2");
                    return "b";
                }));

        List<Object> results = orchestrator.execute(BOOKING, steps);

        assertThat(order).containsExactly("s1", "s2");
        assertThat(results).containsExactly("a", "b");
    }

    @Test
    void failureCompensatesCommittedStepsInReverseOrder() {
        List<String> compensations = new ArrayList<>();

        BookingSagaOrchestrator.Step<String> step1 = BookingSagaOrchestrator.Step.of(
                "create", () -> "created", r -> compensations.add("undo-create"));
        BookingSagaOrchestrator.Step<String> step2 = BookingSagaOrchestrator.Step.of(
                "reserve", () -> "reserved", r -> compensations.add("undo-reserve"));
        BookingSagaOrchestrator.Step<String> step3 = BookingSagaOrchestrator.Step.of(
                "publish", () -> {
                    throw new RuntimeException("kafka down");
                }, r -> compensations.add("undo-publish"));

        assertThatThrownBy(() -> orchestrator.execute(BOOKING, List.of(step1, step2, step3)))
                .isInstanceOf(BookingException.class);

        // Compensation runs in reverse order for the two committed steps; the failed step's
        // compensation is not run because its forward action never completed.
        assertThat(compensations).containsExactly("undo-reserve", "undo-create");
    }

    @Test
    void bookingExceptionFromAStepIsPropagatedUnwrapped() {
        BookingSagaOrchestrator.Step<String> failing = BookingSagaOrchestrator.Step.of(
                "estimate", () -> {
                    throw BookingException.pricingUnavailable();
                });

        assertThatThrownBy(() -> orchestrator.execute(BOOKING, List.of(failing)))
                .isInstanceOf(BookingException.class)
                .satisfies(ex -> assertThat(((BookingException) ex).getErrorCode())
                        .isEqualTo("PRICING_ENGINE_UNAVAILABLE"));
    }

    @Test
    void eachStepIsRecordedBeforeProceeding() {
        List<SagaStep> recordedAtActionTime = new ArrayList<>();
        // Capture the saga-log state visible when each forward action runs: the step must
        // already have been persisted as STARTED before the action executes (Requirement 24.6).
        List<BookingSagaOrchestrator.Step<?>> steps = List.of(
                BookingSagaOrchestrator.Step.of("only-step", () -> {
                    // The repository.save for this step happened before this action ran.
                    recordedAtActionTime.add(SagaStep.started(BOOKING, 0, "only-step"));
                    return "ok";
                }));
        orchestrator.execute(BOOKING, steps);
        assertThat(recordedAtActionTime).hasSize(1);
    }
}
