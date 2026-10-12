package com.homefix.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.support.InMemoryBookingRepository;

/**
 * Web-layer tests for {@link InternalAddressUsageController}: the contract the Customer Service's
 * {@code HttpBookingClientAdapter} reads before deleting a saved address (Requirement 2.6).
 */
class InternalAddressUsageControllerTest {

    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CUSTOMER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID HOME = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID OFFICE = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private InMemoryBookingRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        mvc = MockMvcBuilders.standaloneSetup(new InternalAddressUsageController(repository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void book(String reference, UUID customerId, UUID addressId, BookingStatus status) {
        Booking b = Booking.create(reference, customerId, UUID.randomUUID(), UUID.randomUUID(),
                addressId, false, Instant.now(), new BigDecimal("100.00"));
        b.applyStatus(status);
        repository.save(b);
    }

    @Test
    void reportsTheActiveBookingAtTheAddress() throws Exception {
        book("HFX-20261002-AAAAAA", CUSTOMER, HOME, BookingStatus.PROVIDER_ON_THE_WAY);

        mvc.perform(get("/internal/bookings/active")
                        .param("customerId", CUSTOMER.toString())
                        .param("addressId", HOME.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingReference").value("HFX-20261002-AAAAAA"));
    }

    @Test
    void anAddressNoActiveBookingUsesIsFree() throws Exception {
        book("HFX-20261002-AAAAAA", CUSTOMER, OFFICE, BookingStatus.JOB_STARTED);
        book("HFX-20261002-BBBBBB", OTHER_CUSTOMER, HOME, BookingStatus.JOB_STARTED);

        mvc.perform(get("/internal/bookings/active")
                        .param("customerId", CUSTOMER.toString())
                        .param("addressId", HOME.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingReference").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {
            "SEARCHING_FAILED", "JOB_COMPLETED", "CUSTOMER_CONFIRMED", "PAYMENT_PENDING",
            "PAYMENT_COMPLETED", "DISPUTED", "REFUNDED", "CANCELLED"})
    void bookingsThatNoLongerNeedTheAddressDoNotBlockIt(BookingStatus status) throws Exception {
        book("HFX-20261002-AAAAAA", CUSTOMER, HOME, status);

        mvc.perform(get("/internal/bookings/active")
                        .param("customerId", CUSTOMER.toString())
                        .param("addressId", HOME.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingReference").doesNotExist());
    }

    static Stream<BookingStatus> addressInUseStatuses() {
        return InternalAddressUsageController.ADDRESS_IN_USE_STATUSES.stream();
    }

    @ParameterizedTest
    @MethodSource("addressInUseStatuses")
    void everyStateBetweenSearchAndTheJobBlocksTheAddress(BookingStatus status) throws Exception {
        book("HFX-20261002-CCCCCC", CUSTOMER, HOME, status);

        mvc.perform(get("/internal/bookings/active")
                        .param("customerId", CUSTOMER.toString())
                        .param("addressId", HOME.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingReference").value("HFX-20261002-CCCCCC"));
    }

    /**
     * The set is exactly the states from which the job can still be done, so a state added to the
     * lifecycle later (as AWAITING_ASSIGNMENT was, and was missed here) fails this test until it is
     * classified.
     */
    @Test
    void theInUseStatesAreExactlyThoseThatCanStillReachACompletedJob() {
        BookingStateMachine machine = new BookingStateMachine();
        Set<BookingStatus> beforeCompletion = EnumSet.noneOf(BookingStatus.class);
        for (BookingStatus status : BookingStatus.values()) {
            if (status != BookingStatus.JOB_COMPLETED && reaches(machine, status, BookingStatus.JOB_COMPLETED)) {
                beforeCompletion.add(status);
            }
        }

        assertThat(InternalAddressUsageController.ADDRESS_IN_USE_STATUSES)
                .containsExactlyInAnyOrderElementsOf(beforeCompletion)
                .contains(BookingStatus.AWAITING_ASSIGNMENT, BookingStatus.PROVIDER_ASSIGNED);
    }

    private static boolean reaches(BookingStateMachine machine, BookingStatus from, BookingStatus target) {
        Set<BookingStatus> seen = EnumSet.of(from);
        Deque<BookingStatus> pending = new ArrayDeque<>(Set.of(from));
        while (!pending.isEmpty()) {
            for (BookingStatus next : machine.permittedTargets(pending.pop())) {
                if (next == target) {
                    return true;
                }
                if (seen.add(next)) {
                    pending.push(next);
                }
            }
        }
        return false;
    }
}
