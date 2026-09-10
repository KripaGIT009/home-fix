package com.homefix.booking.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.homefix.booking.domain.SagaStep;
import com.homefix.booking.domain.SagaStepRepository;

/**
 * Minimal Saga orchestrator for the Booking flow (Requirement 24.6, 24.7).
 *
 * <p>Each step is recorded in the Saga log as STARTED <em>before</em> its action runs
 * (Requirement 24.6), then marked COMPLETED. If any step fails, the already-committed steps
 * are compensated in reverse order (Requirement 24.7) and each is marked COMPENSATED; the
 * originating {@link BookingException} (mapped to a booking-not-completed response) is then
 * rethrown.
 *
 * <p>This orchestrator is deliberately independent of the specific step semantics so the
 * scheduled-create, emergency-create, and later job-execution flows (Task 15) can all reuse
 * it.
 */
@Service
public class BookingSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BookingSagaOrchestrator.class);

    private final SagaStepRepository sagaStepRepository;

    public BookingSagaOrchestrator(SagaStepRepository sagaStepRepository) {
        this.sagaStepRepository = sagaStepRepository;
    }

    /**
     * A single saga step: a name, a forward action producing a result, and a compensation
     * that undoes the forward action's effect.
     */
    public static final class Step<T> {
        final String name;
        final Supplier<T> action;
        final Consumer<T> compensation;

        private Step(String name, Supplier<T> action, Consumer<T> compensation) {
            this.name = name;
            this.action = action;
            this.compensation = compensation;
        }

        public static <T> Step<T> of(String name, Supplier<T> action, Consumer<T> compensation) {
            return new Step<>(name, action, compensation);
        }

        /** A step with no meaningful compensation (e.g. an idempotent or read-only step). */
        public static <T> Step<T> of(String name, Supplier<T> action) {
            return new Step<>(name, action, result -> { });
        }
    }

    /** Records a committed step's forward result and its compensation for reverse-order undo. */
    private record CommittedStep<T>(SagaStep record, Consumer<T> compensation, T result) {
        @SuppressWarnings("unchecked")
        void compensate() {
            ((Consumer<Object>) (Consumer<?>) compensation).accept(result);
        }
    }

    /**
     * Executes the ordered {@code steps} for {@code bookingId}, recording the Saga log and
     * compensating on failure.
     *
     * @return the results of each forward action, in order, on success
     * @throws BookingException if any step fails; committed steps are compensated first
     */
    public List<Object> execute(UUID bookingId, List<Step<?>> steps) {
        List<CommittedStep<?>> committed = new ArrayList<>();
        List<Object> results = new ArrayList<>();
        int sequence = 0;
        for (Step<?> step : steps) {
            SagaStep record = sagaStepRepository.save(SagaStep.started(bookingId, sequence++, step.name));
            try {
                Object result = step.action.get();
                record.markCompleted();
                sagaStepRepository.save(record);
                committed.add(new CommittedStep<>(record, cast(step.compensation), result));
                results.add(result);
            } catch (RuntimeException ex) {
                record.markFailed(ex.getMessage());
                sagaStepRepository.save(record);
                log.error("Saga step '{}' failed for booking {}: {}", step.name, bookingId, ex.getMessage());
                compensate(bookingId, committed);
                if (ex instanceof BookingException be) {
                    throw be;
                }
                throw BookingException.sagaFailed(
                        "Booking request was not completed: step '" + step.name + "' failed");
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private static <T> Consumer<T> cast(Consumer<?> c) {
        return (Consumer<T>) c;
    }

    /** Runs compensations in reverse order (Requirement 24.7). */
    private void compensate(UUID bookingId, List<CommittedStep<?>> committed) {
        for (int i = committed.size() - 1; i >= 0; i--) {
            CommittedStep<?> step = committed.get(i);
            try {
                step.compensate();
                step.record().markCompensated();
                sagaStepRepository.save(step.record());
                log.warn("Compensated saga step '{}' for booking {}", step.record().getStepName(), bookingId);
            } catch (RuntimeException ex) {
                log.error("Compensation for saga step '{}' failed for booking {}: {}",
                        step.record().getStepName(), bookingId, ex.getMessage());
            }
        }
    }
}
