import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import type { BankAccount } from '@features/earnings/api';
import { earningsKeys } from '@features/earnings/hooks';
import {
  fetchMyProfile,
  fetchServiceCatalog,
  saveAvailability,
  saveBankAccount,
  saveEmergencyAvailability,
  saveProfile,
  saveServiceRadius,
  type AvailabilitySlot,
  type BankAccountPayload,
  type CatalogCategory,
  type ProfilePayload,
  type ProviderProfile,
} from './api';
import type { ServiceAreaChange } from './schemas';

/** Query keys for the provider's work profile and the catalog it is built from. */
export const profileKeys = {
  me: ['provider', 'profile'] as const,
  catalog: ['catalog', 'categories'] as const,
};

/**
 * The work profile; `null` until the provider has saved one. Read by the
 * profile screens and by the dashboard's "complete your profile" prompt.
 */
export function useMyProfile(): UseQueryResult<ProviderProfile | null, ApiError> {
  return useQuery<ProviderProfile | null, ApiError>({
    queryKey: profileKeys.me,
    queryFn: fetchMyProfile,
  });
}

/**
 * The active service catalog. The Catalog Service serves it from a cache with
 * a 300 s bound, so five minutes client-side adds nothing stale.
 */
export function useServiceCatalog(): UseQueryResult<CatalogCategory[], ApiError> {
  return useQuery<CatalogCategory[], ApiError>({
    queryKey: profileKeys.catalog,
    queryFn: fetchServiceCatalog,
    staleTime: 5 * 60_000,
  });
}

/** Every profile write answers with the full profile, which becomes the cached copy. */
function useProfileMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<ProviderProfile>,
): UseMutationResult<ProviderProfile, ApiError, TVariables> {
  const queryClient = useQueryClient();
  return useMutation<ProviderProfile, ApiError, TVariables>({
    mutationFn,
    onSuccess: (profile) => {
      queryClient.setQueryData(profileKeys.me, profile);
    },
  });
}

/** Create or replace the profile (services, skills, experience, radius, base location). */
export function useSaveProfile(): UseMutationResult<ProviderProfile, ApiError, ProfilePayload> {
  return useProfileMutation(saveProfile);
}

/**
 * Save the base location and radius: PUT /radius when only the radius
 * changed, else PUT /profile (the only endpoint that sets the location).
 */
export function useSaveServiceArea(): UseMutationResult<
  ProviderProfile,
  ApiError,
  ServiceAreaChange
> {
  return useProfileMutation((change: ServiceAreaChange) =>
    change.kind === 'radius'
      ? saveServiceRadius(change.serviceRadiusKm)
      : saveProfile(change.payload),
  );
}

/** Replace the weekly availability schedule. */
export function useSaveAvailability(): UseMutationResult<
  ProviderProfile,
  ApiError,
  AvailabilitySlot[]
> {
  return useProfileMutation(saveAvailability);
}

/** Opt in to or out of emergency jobs. */
export function useSaveEmergencyAvailability(): UseMutationResult<
  ProviderProfile,
  ApiError,
  boolean
> {
  return useProfileMutation(saveEmergencyAvailability);
}

/**
 * Add or replace the bank account, then refresh settlement info: it is where
 * both the profile card and the Earnings settlement form read the account.
 */
export function useSaveBankAccount(): UseMutationResult<BankAccount, ApiError, BankAccountPayload> {
  const queryClient = useQueryClient();
  return useMutation<BankAccount, ApiError, BankAccountPayload>({
    mutationFn: saveBankAccount,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: earningsKeys.settlementInfo });
    },
  });
}
