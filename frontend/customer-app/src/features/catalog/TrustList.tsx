import { Box, Stack, Typography } from '@mui/material';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import SellRoundedIcon from '@mui/icons-material/SellRounded';
import PaymentsRoundedIcon from '@mui/icons-material/PaymentsRounded';
import { brand } from '@lib/theme';

/**
 * HomeFix's promises, stated only where the product enforces them: pros pass
 * document and background checks before their first job, the price is shown
 * before a booking is confirmed, and payment is taken once the job is marked
 * complete. No ratings or customer counts — the platform does not collect them.
 */
const PROMISES = [
  {
    Icon: VerifiedUserRoundedIcon,
    title: 'Verified pros',
    caption: 'Background-checked',
  },
  {
    Icon: SellRoundedIcon,
    title: 'Upfront prices',
    caption: 'Shown before you book',
  },
  {
    Icon: PaymentsRoundedIcon,
    title: 'Pay after the job',
    caption: 'Once the work is done',
  },
] as const;

/**
 * The trust items, as a horizontal strip under the hero or a stacked list in
 * the category page's side card.
 */
export function TrustList({ layout = 'strip' }: { layout?: 'strip' | 'stack' }) {
  const strip = layout === 'strip';
  return (
    <Box
      component="ul"
      sx={{
        listStyle: 'none',
        m: 0,
        p: 0,
        display: 'grid',
        // Natural widths on wider screens, so short lines never wrap inside
        // equal-width columns.
        gridTemplateColumns: strip ? { xs: '1fr', sm: 'repeat(3, max-content)' } : '1fr',
        columnGap: { sm: 4 },
        rowGap: strip ? 1.5 : 2,
      }}
    >
      {PROMISES.map(({ Icon, title, caption }) => (
        <Stack component="li" key={title} direction="row" spacing={1} alignItems="center">
          <Box
            aria-hidden
            sx={{
              width: 32,
              height: 32,
              flexShrink: 0,
              borderRadius: '50%',
              display: 'grid',
              placeItems: 'center',
              bgcolor: brand.slateSoft,
              color: 'text.primary',
            }}
          >
            <Icon sx={{ fontSize: 17 }} />
          </Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="body2" fontWeight={700} sx={{ lineHeight: 1.35 }}>
              {title}
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              display="block"
              sx={{ fontSize: '0.75rem', lineHeight: 1.4 }}
            >
              {caption}
            </Typography>
          </Box>
        </Stack>
      ))}
    </Box>
  );
}
