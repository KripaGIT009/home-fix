import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Card,
  CardActionArea,
  Stack,
  Typography,
} from '@mui/material';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import { AppShell } from '@components/AppShell';
import { IconTile } from '@components/StateViews';
import { brand } from '@lib/theme';

/**
 * Questions answered inline, sourced from the platform rules customers ask
 * about most: pricing transparency, verification, cancellation and payment.
 */
const FAQS = [
  {
    question: 'How is the price decided?',
    answer:
      'You see a fully itemised estimate — base price, any emergency or surge charge, night and weekend surcharges, platform fee and taxes — before you confirm. If the professional needs parts, the updated total comes back to you for approval before it is charged.',
  },
  {
    question: 'What does “verified” mean?',
    answer:
      'Verified professionals have passed document checks and a background check before they can accept work. The badge on a profile means those checks are approved and current.',
  },
  {
    question: 'Can I cancel a booking?',
    answer:
      'Yes. Cancel from the booking detail screen. A cancellation fee may apply once a professional is on the way; the amount is shown before you confirm the cancellation.',
  },
  {
    question: 'When am I charged?',
    answer:
      'After the job is completed and you confirm it. The invoice is then generated and available to download from the booking detail screen.',
  },
] as const;

/**
 * Help & support screen reached from the main navigation. Answers the common
 * questions inline and routes the two things a customer in trouble actually
 * wants: the booking a problem relates to, and the emergency path.
 */
export function HelpScreen() {
  const navigate = useNavigate();

  return (
    <AppShell>
      <Stack spacing={{ xs: 3, md: 4 }}>
        <Box>
          <Typography variant="h2" component="h1">
            How can we help?
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mt: 0.5 }}>
            Answers to the questions we get most, and a quick way to the right place.
          </Typography>
        </Box>

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' },
            gap: 1.5,
          }}
        >
          <ActionCard
            icon={<ReceiptLongRoundedIcon />}
            title="A problem with a booking"
            caption="Open the job to track it, chat or get the invoice"
            onClick={() => navigate('/history')}
          />
          <ActionCard
            icon={<BoltRoundedIcon />}
            title="Emergency help"
            caption="Book a 24×7 service and get a pro now"
            tone="danger"
            onClick={() => navigate('/home')}
          />
        </Box>

        <Box component="section" aria-labelledby="faq-title">
          <Typography id="faq-title" variant="h4" component="h2" sx={{ mb: 2 }}>
            Frequently asked
          </Typography>
          <Stack spacing={1.25}>
            {FAQS.map((faq) => (
              <Accordion key={faq.question} disableGutters elevation={0}>
                <AccordionSummary expandIcon={<ExpandMoreRoundedIcon />} sx={{ px: 2.5, py: 0.5 }}>
                  <Typography variant="subtitle1" fontWeight={700}>
                    {faq.question}
                  </Typography>
                </AccordionSummary>
                <AccordionDetails sx={{ px: 2.5, pb: 2.5, pt: 0 }}>
                  <Typography variant="body1" color="text.secondary">
                    {faq.answer}
                  </Typography>
                </AccordionDetails>
              </Accordion>
            ))}
          </Stack>
        </Box>
      </Stack>
    </AppShell>
  );
}

/** One of the two large entry points at the top of the help screen. */
function ActionCard({
  icon,
  title,
  caption,
  onClick,
  tone = 'primary',
}: {
  icon: ReactNode;
  title: string;
  caption: string;
  onClick: () => void;
  tone?: 'primary' | 'danger';
}) {
  const isDanger = tone === 'danger';
  return (
    <Card sx={{ borderColor: isDanger ? '#F7D4D0' : undefined }}>
      <CardActionArea
        onClick={onClick}
        sx={{ height: '100%', p: { xs: 2, md: 2.5 } }}
        aria-label={title}
      >
        <Stack direction="row" spacing={2} alignItems="center">
          <IconTile
            size={48}
            bg={isDanger ? brand.redSoft : brand.accentSoft}
            color={isDanger ? brand.red : brand.accent}
          >
            {icon}
          </IconTile>
          <Box sx={{ flexGrow: 1 }}>
            <Typography variant="subtitle1" fontWeight={700} lineHeight={1.3}>
              {title}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {caption}
            </Typography>
          </Box>
          <ArrowForwardRoundedIcon sx={{ color: 'text.disabled' }} aria-hidden />
        </Stack>
      </CardActionArea>
    </Card>
  );
}
