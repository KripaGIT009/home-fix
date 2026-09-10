import { QueryClient } from '@tanstack/react-query';
import { isApiError } from '@api/client';

/**
 * Shared TanStack Query client.
 * - staleTime: 30s so navigating between screens doesn't refetch constantly.
 * - retry: up to 2 attempts, but never retry client errors (4xx) since those
 *   are deterministic (bad input, unauthorized, not found).
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        if (isApiError(error) && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
    },
    mutations: {
      retry: false,
    },
  },
});
