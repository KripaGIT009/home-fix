import { Box, Stack, Typography } from '@mui/material';
import TouchAppRoundedIcon from '@mui/icons-material/TouchAppRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import NearMeRoundedIcon from '@mui/icons-material/NearMeRounded';
import { brand, radius, visuallyHidden } from '@lib/theme';
import { SectionHeading } from './SectionHeading';

const STEPS = [
  {
    Icon: TouchAppRoundedIcon,
    title: 'Pick a service',
    body: 'Choose what you need and tell us where and when. Add photos if it helps.',
  },
  {
    Icon: ReceiptLongRoundedIcon,
    title: 'See the price upfront',
    body: 'Get an itemised estimate before you confirm. No surprises later.',
  },
  {
    Icon: NearMeRoundedIcon,
    title: 'Track your pro',
    body: 'Follow them live to your door, chat in the app, and pay when the job is done.',
  },
] as const;

/** Three steps in one compact row: how a booking goes from tap to done. */
export function HowItWorks() {
  return (
    <Box component="section" aria-labelledby="how-it-works-title">
      <SectionHeading id="how-it-works-title" title="How HomeFix works" />
      <Box
        component="ol"
        sx={{
          listStyle: 'none',
          m: 0,
          p: { xs: 2, md: 2.5 },
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
          gap: { xs: 2, md: 3 },
          border: `1px solid ${brand.line}`,
          borderRadius: `${radius.md}px`,
        }}
      >
        {STEPS.map(({ Icon, title, body }, index) => (
          <Stack component="li" key={title} direction="row" spacing={1.5}>
            <Box
              aria-hidden
              sx={{
                width: 36,
                height: 36,
                flexShrink: 0,
                borderRadius: '50%',
                display: 'grid',
                placeItems: 'center',
                bgcolor: brand.accentSoft,
                color: 'primary.main',
              }}
            >
              <Icon sx={{ fontSize: 18 }} />
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="body2" fontWeight={700} component="h3">
                <Box component="span" sx={visuallyHidden}>
                  Step {index + 1}:{' '}
                </Box>
                <Box component="span" aria-hidden sx={{ color: 'text.secondary', mr: 0.75 }}>
                  {index + 1}.
                </Box>
                {title}
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ mt: 0.25, lineHeight: 1.5 }}
              >
                {body}
              </Typography>
            </Box>
          </Stack>
        ))}
      </Box>
    </Box>
  );
}
