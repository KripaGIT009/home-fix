import type { ReactNode } from 'react';
import { Skeleton, Stack } from '@mui/material';
import InboxRoundedIcon from '@mui/icons-material/InboxRounded';
import { radius } from '@lib/theme';
import { EmptyState, InlineError } from './StateViews';

interface QueryStateViewProps {
  isLoading: boolean;
  isError: boolean;
  error?: unknown;
  onRetry?: () => void;
  /** Shown when not loading, not error, and there is no data. */
  isEmpty?: boolean;
  emptyMessage?: string;
  /** Headline for the empty state; the message becomes its supporting copy. */
  emptyTitle?: string;
  /** Optional action (e.g. "Book a service") under the empty state. */
  emptyAction?: ReactNode;
  emptyIcon?: ReactNode;
  /** Number and height of skeleton rows while loading. */
  skeletonRows?: number;
  skeletonHeight?: number;
  children: ReactNode;
}

/**
 * Renders the standard loading / error / empty states around query-backed
 * content, so screens don't repeat the boilerplate. Loading shows skeleton
 * rows shaped like the content; errors are a calm inline notice with a retry.
 */
export function QueryStateView({
  isLoading,
  isError,
  error,
  onRetry,
  isEmpty,
  emptyMessage = 'Nothing to show yet.',
  emptyTitle,
  emptyAction,
  emptyIcon,
  skeletonRows = 3,
  skeletonHeight = 88,
  children,
}: QueryStateViewProps) {
  if (isLoading) {
    return (
      <Stack spacing={1.5} aria-busy="true" aria-label="Loading">
        {Array.from({ length: skeletonRows }, (_, index) => (
          <Skeleton
            key={index}
            variant="rounded"
            height={skeletonHeight}
            sx={{ borderRadius: `${radius.lg}px` }}
          />
        ))}
      </Stack>
    );
  }

  if (isError) {
    return <InlineError error={error} {...(onRetry ? { onRetry } : {})} />;
  }

  if (isEmpty) {
    return (
      <EmptyState
        icon={emptyIcon ?? <InboxRoundedIcon />}
        title={emptyTitle ?? emptyMessage}
        {...(emptyTitle ? { description: emptyMessage } : {})}
        {...(emptyAction ? { action: emptyAction } : {})}
      />
    );
  }

  return <>{children}</>;
}
