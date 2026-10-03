package com.homefix.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.provider.bank.BankAccountCodec;
import com.homefix.provider.bank.BankAccountDetails;
import com.homefix.provider.bank.BankAccountView;
import com.homefix.provider.crypto.LocalAesKmsAdapter;
import com.homefix.provider.domain.ProviderProfile;
import com.homefix.provider.service.ProviderAdminService.AdminProviderView;
import com.homefix.provider.service.ProviderAdminService.ProviderSummaryView;
import com.homefix.provider.support.InMemoryProviderProfileRepository;
import com.homefix.provider.verification.VerificationAdminClientPort;

/**
 * Unit tests for {@link ProviderAdminService} (Requirement 19.2, 19.3) against the in-memory
 * profile repository and a scripted Verification Service port: the bounded, searchable list with
 * one batch status lookup, the soft failure when that lookup is unavailable, status changes
 * delegated to the suspend / reinstate transitions with the acting Admin, and the refusals.
 */
class ProviderAdminServiceTest {

    private final UUID admin = UUID.randomUUID();

    private InMemoryProviderProfileRepository repository;
    private FakeVerification verification;
    private BankAccountCodec codec;
    private ProviderAdminService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryProviderProfileRepository();
        verification = new FakeVerification();
        codec = new BankAccountCodec(new LocalAesKmsAdapter("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
        service = new ProviderAdminService(repository, verification, codec);
    }

    private ProviderProfile persist(String name, String... tags) {
        ProviderProfile p = ProviderProfile.createWithId(UUID.randomUUID());
        p.setDisplayName(name);
        p.replaceSkillTags(List.of(tags));
        p.applyAggregateRating(new BigDecimal("4.5"), new BigDecimal("3.0"));
        return repository.save(p);
    }

    // ------------------------------------------------------------------ list

    @Test
    void list_isNewestFirstWithPrimarySkillAndOneBatchStatusLookup() {
        ProviderProfile older = persist("Asha Electricals", "electrical", "wiring");
        ProviderProfile newer = persist("Ravi Plumbing", "plumbing");
        verification.statuses.put(older.getId(), "APPROVED");

        List<AdminProviderView> list = service.list(null);

        assertThat(list).extracting(v -> v.row().id()).containsExactly(newer.getId(), older.getId());
        assertThat(list.get(1).primarySkill()).isEqualTo("electrical");
        assertThat(list.get(1).row().aggregateRating()).isEqualByComparingTo("4.5");
        assertThat(list.get(1).verificationKnown()).isTrue();
        assertThat(list.get(1).verificationStatus()).isEqualTo("APPROVED");
        // No verification record: known, but no status.
        assertThat(list.get(0).verificationKnown()).isTrue();
        assertThat(list.get(0).verificationStatus()).isNull();
        assertThat(verification.statusLookups).isEqualTo(1);
    }

    @Test
    void list_searchIsACaseInsensitiveSubstringOfTheDisplayName() {
        ProviderProfile ravi = persist("Ravi Plumbing", "plumbing");
        persist("Asha Electricals", "electrical");

        assertThat(service.list("  PLUMB ")).extracting(v -> v.row().id()).containsExactly(ravi.getId());
        assertThat(service.list("nobody")).isEmpty();
    }

    @Test
    void list_treatsLikeMetacharactersLiterally() {
        assertThat(ProviderAdminService.likePattern("50%_off\\"))
                .isEqualTo("%50\\%\\_off\\\\%");
    }

    @Test
    void list_isCappedServerSide() {
        for (int i = 0; i < ProviderAdminService.ADMIN_LIST_LIMIT + 3; i++) {
            persist("Provider " + i, "plumbing");
        }

        assertThat(service.list(null)).hasSize(ProviderAdminService.ADMIN_LIST_LIMIT);
    }

    @Test
    void list_whenVerificationIsUnavailable_isStillReturnedWithUnknownStatuses() {
        persist("Ravi Plumbing", "plumbing");
        verification.available = false;

        List<AdminProviderView> list = service.list(null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).verificationKnown()).isFalse();
        assertThat(list.get(0).verificationStatus()).isNull();
    }

    @Test
    void list_ofNoProviders_makesNoVerificationCall() {
        assertThat(service.list(null)).isEmpty();
        assertThat(verification.statusLookups).isZero();
    }

    // ------------------------------------------------------------------ status change

    @Test
    void suspended_drivesTheSuspendTransitionWithTheActingAdmin() {
        ProviderProfile p = persist("Ravi Plumbing", "plumbing");

        AdminProviderView view = service.changeStatus(p.getId(), "SUSPENDED", admin);

        assertThat(verification.calls).containsExactly("suspend " + p.getId() + " by " + admin);
        assertThat(view.verificationKnown()).isTrue();
        assertThat(view.verificationStatus()).isEqualTo("SUSPENDED");
        assertThat(view.primarySkill()).isEqualTo("plumbing");
    }

    @Test
    void active_drivesTheReinstateTransition_caseInsensitively() {
        ProviderProfile p = persist("Ravi Plumbing", "plumbing");

        AdminProviderView view = service.changeStatus(p.getId(), "active", admin);

        assertThat(verification.calls).containsExactly("reinstate " + p.getId() + " by " + admin);
        assertThat(view.verificationStatus()).isEqualTo("APPROVED");
    }

    @Test
    void deactivated_hasNoBackingTransitionAndIsRefused() {
        ProviderProfile p = persist("Ravi Plumbing", "plumbing");

        assertThatThrownBy(() -> service.changeStatus(p.getId(), "DEACTIVATED", admin))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    assertThat(((ProviderException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ProviderException) e).getErrorCode()).isEqualTo("UNSUPPORTED_PROVIDER_STATUS");
                });
        assertThat(verification.calls).isEmpty();
    }

    @Test
    void unknownStatus_isAValidationError() {
        ProviderProfile p = persist("Ravi Plumbing", "plumbing");

        assertThatThrownBy(() -> service.changeStatus(p.getId(), "BANISHED", admin))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getErrorCode()).isEqualTo("VALIDATION_ERROR"));
        assertThat(verification.calls).isEmpty();
    }

    @Test
    void unknownProvider_isNotFoundAndVerificationIsNotCalled() {
        assertThatThrownBy(() -> service.changeStatus(UUID.randomUUID(), "SUSPENDED", admin))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getErrorCode()).isEqualTo("PROVIDER_NOT_FOUND"));
        assertThat(verification.calls).isEmpty();
    }

    @Test
    void verificationsRefusal_propagates() {
        ProviderProfile p = persist("Ravi Plumbing", "plumbing");
        verification.refusal = new ProviderException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Transition from DOCUMENT_SUBMITTED to SUSPENDED is not permitted");

        assertThatThrownBy(() -> service.changeStatus(p.getId(), "SUSPENDED", admin))
                .isSameAs(verification.refusal);
    }

    // ------------------------------------------------------------------ summaries

    @Test
    void summaries_returnKnownProvidersOnly() {
        ProviderProfile ravi = persist("Ravi Plumbing", "plumbing", "drains");

        List<ProviderSummaryView> summaries = service.summaries(List.of(ravi.getId(), UUID.randomUUID()));

        assertThat(summaries).containsExactly(new ProviderSummaryView(ravi.getId(), "Ravi Plumbing", "plumbing"));
        assertThat(service.summaries(null)).isEmpty();
    }

    @Test
    void summaries_overTheCap_areAValidationError() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i <= ProviderAdminService.MAX_SUMMARY_IDS; i++) {
            ids.add(UUID.randomUUID());
        }

        assertThatThrownBy(() -> service.summaries(ids))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getErrorCode()).isEqualTo("VALIDATION_ERROR"));
    }

    /** Scripted Verification Service: records calls, answers from {@link #statuses}. */
    // ------------------------------------------------------------------ bank account

    private void storeAccount(ProviderProfile p, boolean verified) {
        p.setBankAccount(codec.seal(new BankAccountDetails("Ravi Kumar", "50100123456789", "HDFC0001234")),
                verified);
        repository.save(p);
    }

    @Test
    void list_showsTheBankAccountMaskedOrNullWhenNone() {
        ProviderProfile without = persist("Asha Electricals", "electrical");
        ProviderProfile with = persist("Ravi Plumbing", "plumbing");
        storeAccount(with, false);

        List<AdminProviderView> list = service.list(null);

        AdminProviderView ravi = list.stream().filter(v -> v.row().id().equals(with.getId())).findFirst().orElseThrow();
        AdminProviderView asha = list.stream().filter(v -> v.row().id().equals(without.getId())).findFirst().orElseThrow();
        assertThat(ravi.bankAccount().masked()).isEqualTo("HDFC ••••6789");
        assertThat(ravi.bankAccount().verified()).isFalse();
        assertThat(asha.bankAccount()).isNull();
    }

    @Test
    void verifyBankAccount_marksTheStoredAccountVerifiedWithoutChangingIt() {
        ProviderProfile p = persist("Ravi Plumbing", "plumbing");
        storeAccount(p, false);
        String stored = repository.findById(p.getId()).orElseThrow().getBankAccountEncrypted();

        BankAccountView view = service.verifyBankAccount(p.getId(), admin);

        ProviderProfile after = repository.findById(p.getId()).orElseThrow();
        assertThat(after.isBankAccountVerified()).isTrue();
        assertThat(after.getBankAccountEncrypted()).isEqualTo(stored);
        assertThat(view.verified()).isTrue();
        assertThat(view.masked()).isEqualTo("HDFC ••••6789");
        assertThat(view.holderName()).isEqualTo("Ravi Kumar");
        // Idempotent.
        assertThat(service.verifyBankAccount(p.getId(), admin).verified()).isTrue();
    }

    @Test
    void verifyBankAccount_withNoAccountOnFile_is404BankAccountNotFound() {
        ProviderProfile p = persist("Ravi Plumbing", "plumbing");

        assertThatThrownBy(() -> service.verifyBankAccount(p.getId(), admin))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("BANK_ACCOUNT_NOT_FOUND");
                });
        assertThat(repository.findById(p.getId()).orElseThrow().isBankAccountVerified()).isFalse();
    }

    @Test
    void verifyBankAccount_forAnUnknownProvider_is404ProviderNotFound() {
        assertThatThrownBy(() -> service.verifyBankAccount(UUID.randomUUID(), admin))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("PROVIDER_NOT_FOUND");
                });
    }

    private static final class FakeVerification implements VerificationAdminClientPort {

        final Map<UUID, String> statuses = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean available = true;
        int statusLookups;
        ProviderException refusal;

        @Override
        public Optional<Map<UUID, String>> statusesOf(Collection<UUID> providerIds) {
            statusLookups++;
            if (!available) {
                return Optional.empty();
            }
            Map<UUID, String> found = new HashMap<>(statuses);
            found.keySet().retainAll(providerIds);
            return Optional.of(found);
        }

        @Override
        public String suspend(UUID providerId, UUID actorId, String reason) {
            if (refusal != null) {
                throw refusal;
            }
            calls.add("suspend " + providerId + " by " + actorId);
            return "SUSPENDED";
        }

        @Override
        public String reinstate(UUID providerId, UUID actorId, String reason) {
            if (refusal != null) {
                throw refusal;
            }
            calls.add("reinstate " + providerId + " by " + actorId);
            return "APPROVED";
        }
    }
}
