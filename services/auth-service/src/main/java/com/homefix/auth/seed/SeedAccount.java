package com.homefix.auth.seed;

import java.util.Set;

import com.homefix.auth.domain.Role;

/**
 * One local test account: the console username, the mobile number that reaches the same
 * account through the OTP flow, and the roles it holds.
 *
 * @param username     console username for password sign-in, lower case
 * @param mobileNumber E.164 number, so OTP and password sign-in land on one account
 * @param roles        every role the account holds, staff roles included
 */
public record SeedAccount(String username, String mobileNumber, Set<Role> roles) {
}
