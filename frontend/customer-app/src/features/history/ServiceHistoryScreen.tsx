import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Button, Card, CardActionArea, Pagination, Stack, Typography } from '@mui/material';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import EventNoteRoundedIcon from '@mui/icons-material/EventNoteRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { IconTile } from '@components/StateViews';
import { brand, shadows } from '@lib/theme';
import { formatCurrency, formatDate } from '@lib/format';
import type { BookingHistoryItem } from './api';
import { useBookingHistory } from './hooks';
import { StatusChip } from './StatusChip';
import { bookingStatusTone, STATUS_TONE_COLORS } from './status';

/**
 * Service History screen (Requirement 28.7). Shows a paginated list of the
 * Customer's bookings with status, date, and amount. Tapping a row opens the
 * booking detail, where the invoice PDF can be downloaded.
 */
export function ServiceHistoryScreen() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);

  const query = useBookingHistory(page);
  const total = query.data?.totalItems ?? 0;

  return (
    <AppShell>
      <Stack spacing={{ xs: 2.5, md: 4 }}>
        <Stack direction="row" alignItems="flex-end" justifyContent="space-between" spacing={2}>
          <Box>
            <Typography variant="h2" component="h1">
              Your bookings
            </Typography>
            <Typography variant="body1" color="text.secondary" sx={{ mt: 0.5 }}>
              {total > 0
                ? `${total} booking${total === 1 ? '' : 's'}, newest first.`
                : 'Every job you book, with its status and invoice.'}
            </Typography>
          </Box>
          <Button
            variant="outlined"
            onClick={() => navigate('/home')}
            sx={{ display: { xs: 'none', sm: 'inline-flex' }, flexShrink: 0 }}
          >
            Book a service
          </Button>
        </Stack>

        <QueryStateView
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          onRetry={() => void query.refetch()}
          isEmpty={!query.data || query.data.items.length === 0}
          emptyIcon={<EventNoteRoundedIcon />}
          emptyTitle="No bookings yet"
          emptyMessage="When you book a service it shows up here, with live status and your invoice."
          emptyAction={
            <Button variant="contained" size="large" onClick={() => navigate('/home')}>
              Book a service
            </Button>
          }
        >
          <Stack spacing={1.5} sx={{ opacity: query.isPlaceholderData ? 0.6 : 1 }}>
            {query.data?.items.map((item) => (
              <HistoryRow
                key={item.bookingId}
                item={item}
                onOpen={() => navigate(`/bookings/${item.bookingId}`)}
              />
            ))}
          </Stack>

          {query.data && query.data.totalPages > 1 ? (
            <Stack alignItems="center" sx={{ mt: 4 }}>
              <Pagination
                count={query.data.totalPages}
                page={page}
                onChange={(_event, value) => setPage(value)}
                color="primary"
                shape="rounded"
                aria-label="Booking pages"
              />
            </Stack>
          ) : null}
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

function HistoryRow({ item, onOpen }: { item: BookingHistoryItem; onOpen: () => void }) {
  const tone = STATUS_TONE_COLORS[bookingStatusTone(item.status)];
  return (
    <Card sx={{ transition: 'box-shadow .2s', '&:hover': { boxShadow: shadows.card } }}>
      <CardActionArea
        onClick={onOpen}
        sx={{ p: { xs: 2, md: 2.5 } }}
        aria-label={`${item.serviceName}, ${formatDate(item.date)}, booking ${item.referenceNumber}`}
      >
        <Stack direction="row" spacing={2} alignItems="center">
          <IconTile size={48} bg={tone.bg} color={tone.fg}>
            <ReceiptLongRoundedIcon />
          </IconTile>

          <Box sx={{ minWidth: 0, flexGrow: 1 }}>
            <Typography variant="subtitle1" fontWeight={700} noWrap>
              {item.serviceName}
            </Typography>
            <Typography variant="body2" color="text.secondary" noWrap>
              {formatDate(item.date)}
              <Box component="span" sx={{ mx: 0.75, color: brand.subtle }}>
                ·
              </Box>
              {item.referenceNumber}
            </Typography>
            <Box sx={{ mt: 0.75, display: { xs: 'block', sm: 'none' } }}>
              <StatusChip status={item.status} />
            </Box>
          </Box>

          <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
            <StatusChip status={item.status} />
          </Box>
          <Typography
            variant="subtitle1"
            fontWeight={800}
            sx={{ flexShrink: 0, minWidth: { sm: 96 }, textAlign: 'right' }}
          >
            {formatCurrency(item.amount, item.currency)}
          </Typography>
          <ChevronRightRoundedIcon
            sx={{ color: 'text.disabled', display: { xs: 'none', sm: 'block' } }}
          />
        </Stack>
      </CardActionArea>
    </Card>
  );
}
