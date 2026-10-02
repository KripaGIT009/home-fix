package com.homefix.booking.address;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/test {@link CustomerAddressPort} that resolves nothing, so a booking read simply carries no
 * address. Active unless {@code homefix.customer.client=http}.
 */
@Component
@ConditionalOnProperty(name = "homefix.customer.client", havingValue = "stub", matchIfMissing = true)
public class StubCustomerAddressAdapter implements CustomerAddressPort {

    @Override
    public Optional<ServiceAddress> find(UUID addressId) {
        return Optional.empty();
    }
}
