package com.homefix.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.auth.config.AuthTokenProperties;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.support.InMemoryRefreshTokenStore;
import com.homefix.shared.security.SecurityProperties;

/**
 * Refresh rotation under concurrency (CODEBASE_REVIEW.md 8.2; Requirement 1.9, 1.10,
 * Property 26).
 *
 * <p>Many threads present the same refresh token at the same instant. Rotation is single-use, so
 * exactly one may consume it; every other presentation is a replay and must fail with a 401 and
 * revoke the family. Because the replay revokes the family, the winner's successor must not
 * survive either — whichever order the winner's save and the loser's revoke land in.
 *
 * <p>The store is the synchronized in-memory fake, whose {@code consume} is all-or-nothing like
 * the Redis Lua script; a counting wrapper records how many {@code consume} calls observed the
 * token unused, which is the property the old read-check-write sequence violated.
 */
class RefreshRotationConcurrencyTest {

    private static final String SECRET = "unit-test-signing-secret-that-is-32b+";
    private static final int THREADS = 16;

    private CountingStore store;
    private TokenService tokenService;
    private RefreshService refreshService;
    private UUID userId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        SecurityProperties security = new SecurityProperties();
        security.setJwtSecret(SECRET);
        AuthTokenProperties tokenProps = new AuthTokenProperties();
        tokenProps.setAccessTtl(Duration.ofMinutes(15));
        tokenProps.setRefreshTtl(Duration.ofDays(30));

        store = new CountingStore();
        tokenService = new TokenService(security, tokenProps, store);

        UserAccount account = UserAccount.createVerified("+911111111111", Role.CUSTOMER);
        userId = account.getId();
        UserAccountRepository repository = mock(UserAccountRepository.class);
        lenient().when(repository.findById(any(UUID.class))).thenReturn(Optional.of(account));
        refreshService = new RefreshService(tokenService, repository);

        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @RepeatedTest(25)
    void concurrentRefreshesOfOneToken_atMostOneSucceedsAndNoFamilyTokenSurvives() throws Exception {
        String token = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> outcomes = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            outcomes.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return refreshService.refresh(token);
                } catch (TokenException ex) {
                    return ex;
                }
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<RefreshService.RefreshResult> successes = new ArrayList<>();
        List<TokenException> failures = new ArrayList<>();
        for (Future<Object> outcome : outcomes) {
            Object result = outcome.get(10, TimeUnit.SECONDS);
            if (result instanceof RefreshService.RefreshResult ok) {
                successes.add(ok);
            } else {
                failures.add((TokenException) result);
            }
        }

        // Exactly one presentation consumed the token; the rest saw it already used (or gone).
        assertThat(store.unusedObservations.get()).isEqualTo(1);
        assertThat(successes).hasSizeLessThanOrEqualTo(1);
        assertThat(successes.size() + failures.size()).isEqualTo(THREADS);
        assertThat(failures).allSatisfy(ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(ex.getErrorCode()).isIn("REFRESH_TOKEN_REPLAY", "REFRESH_TOKEN_INVALID");
        });
        // With THREADS > 1 at least one loser saw the used token, so replay was detected.
        assertThat(failures).anySatisfy(ex ->
                assertThat(ex.getErrorCode()).isEqualTo("REFRESH_TOKEN_REPLAY"));

        // The family is dead: neither the original nor any successor that was issued can rotate.
        assertThatThrownBy(() -> refreshService.refresh(token)).isInstanceOf(TokenException.class);
        for (RefreshService.RefreshResult ok : successes) {
            assertThatThrownBy(() -> refreshService.refresh(ok.tokens().refreshToken()))
                    .isInstanceOf(TokenException.class)
                    .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                            .isEqualTo("REFRESH_TOKEN_INVALID"));
        }
    }

    @Test
    void successorSavedAfterConcurrentReplayRevokedTheFamily_isRefused() {
        // Deterministic version of the losing interleaving: the winner consumes, a replay revokes
        // the family, and only then does the winner try to store its successor.
        String familyId = UUID.randomUUID().toString();
        String token = tokenService.issueRefreshToken(userId.toString(), familyId);

        RefreshTokenRecord consumed = tokenService.consumeRefreshTokenForRotation(token);
        assertThatThrownBy(() -> tokenService.consumeRefreshTokenForRotation(token))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_REPLAY"));

        assertThatThrownBy(() -> tokenService.issueRotatedTokens(consumed, List.of("CUSTOMER")))
                .isInstanceOf(TokenException.class)
                .satisfies(ex -> assertThat(((TokenException) ex).getErrorCode())
                        .isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void consumedRecordIsReportedAsUsed() {
        String token = tokenService.issueRefreshToken(userId.toString(), UUID.randomUUID().toString());

        RefreshTokenRecord consumed = tokenService.consumeRefreshTokenForRotation(token);

        assertThat(consumed.used()).isTrue();
        assertThat(consumed.subject()).isEqualTo(userId.toString());
        assertThat(tokenService.findRefreshToken(token)).get()
                .satisfies(r -> assertThat(r.used()).isTrue());
    }

    /** Counts how many consume calls observed the token unused, i.e. how many "won". */
    private static final class CountingStore extends InMemoryRefreshTokenStore {
        final AtomicInteger unusedObservations = new AtomicInteger();

        @Override
        public synchronized Optional<RefreshTokenRecord> consume(String token, Duration ttl) {
            Optional<RefreshTokenRecord> prior = super.consume(token, ttl);
            if (prior.isPresent() && !prior.get().used()) {
                unusedObservations.incrementAndGet();
            }
            return prior;
        }
    }
}
