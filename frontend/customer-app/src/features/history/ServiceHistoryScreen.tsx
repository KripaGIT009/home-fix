import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Card, CardActionArea, Chip, Pagination, Stack, Typography } from '@mui/material';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { brand } from '@lib/theme';
import { formatCurrency, formatDate } from '@lib/format';
import type { BookingHistoryItem } from './api';
import { useBookingHistory } from './hooks';
import { bookingStatusColor, bookingStatusLabel } from './status';

/**
 * Service History screen (Requirement 28.7). Shows a paginated list of the
 * Customer's past bookings with status, date, and amount. Tapping a row opens
 * the booking detail, where the invoice PDF can be downloaded.
 */
export function ServiceHistoryScreen() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);

  const query = useBookingHistory(page);

  return (
    <AppShell>
      <Stack spacing={2}>
        <Box>
          <Typography variant="h4" component="h1">
            Your bookings
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Every job you have booked, with its invoice.
          </Typography>
        </Box>

        <QueryStateView
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          onRetry={() => void query.refetch()}
          isEmpty={!query.data || query.data.items.length === 0}
          emptyMessage="You haven't booked any services yet."
        >
          <Stack spacing={1.25}>
            {query.data?.items.map((item) => (
              <HistoryRow
                key={item.bookingId}
                item={item}
                onOpen={() => navigate(`/bookings/${item.bookingId}`)}
              />
            ))}
          </Stack>

          {query.data && query.data.totalPages > 1 ? (
            <Stack alignItems="center" sx={{ mt: 3 }}>
              <Pagination
                count={query.data.totalPages}
                page={page}
                onChange={(_event, value) => setPage(value)}
                color="primary"
                aria-label="Service history pages"
              />
            </Stack>
          ) : null}
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

function HistoryRow({ item, onOpen }: { item: BookingHistoryItem; onOpen: () => void }) {
  return (
    <Card>
      <CardActionArea
        onClick={onOpen}
        sx={{ p: 2 }}
        aria-label={`View booking ${item.referenceNumber}`}
      >
        <Stack direction="row" spacing={1.5} alignItems="center">
          <Box
            sx={{
              width: 40,
              height: 40,
              borderRadius: 2,
              display: 'grid',
              placeItems: 'center',
              bgcolor: brand.accentSoft,
              color: 'primary.main',
              flexShrink: 0,
            }}
          >
            <ReceiptLongRoundedIcon sx={{ fontSize: 20 }} aria-hidden />
          </Box>

          <Box sx={{ minWidth: 0, flexGrow: 1 }}>
            <Typography variant="subtitle1" fontWeight={600} noWrap>
              {item.serviceName}
            </Typography>
            <Typography variant="caption" color="text.secondary" display="block">
              {formatDate(item.date)} · {item.referenceNumber}
            </Typography>
            <Chip
              size="small"
              sx={{ mt: 0.75 }}
              color={bookingStatusColor(item.status)}
              label={bookingStatusLabel(item.status)}
            />
          </Box>

          <Stack alignItems="flex-end" sx={{ flexShrink: 0 }}>
            <Typography variant="subtitle1" fontWeight={700}>
              {formatCurrency(item.amount, item.currency)}
            </Typography>
          </Stack>
          <ChevronRightRoundedIcon sx={{ color: 'text.secondary' }} />
        </Stack>
      </CardActionArea>
    </Card>
  );
}
