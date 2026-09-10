import { useNavigate } from 'react-router-dom';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Card,
  CardActionArea,
  CardContent,
  Stack,
  Typography,
} from '@mui/material';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { AppShell } from '@components/AppShell';
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
 * Help & support screen reached from the bottom navigation. Answers the common
 * questions inline and routes the two things a customer in trouble actually
 * wants: the booking a problem relates to, and the emergency path.
 */
export function HelpScreen() {
  const navigate = useNavigate();

  return (
    <AppShell>
      <Stack spacing={2}>
        <Box>
          <Typography variant="h4" component="h1">
            Help &amp; support
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Answers to the questions we get most, and a way through to us.
          </Typography>
        </Box>

        <Stack direction="row" spacing={1.5}>
          <ActionCard
            icon={<ReceiptLongRoundedIcon />}
            title="A booking issue"
            caption="Open the job"
            onClick={() => navigate('/history')}
          />
          <ActionCard
            icon={<BoltRoundedIcon />}
            title="Emergency help"
            caption="Book 24×7 now"
            tone="danger"
            onClick={() => navigate('/home')}
          />
        </Stack>

        <Box>
          <Typography variant="overline" color="text.secondary">
            Frequently asked
          </Typography>
          <Stack spacing={1} sx={{ mt: 1 }}>
            {FAQS.map((faq) => (
              <Accordion
                key={faq.question}
                disableGutters
                elevation={0}
                sx={{
                  border: `1px solid ${brand.line}`,
                  borderRadius: 3,
                  '&::before': { display: 'none' },
                  '&.Mui-expanded': { margin: 0 },
                }}
              >
                <AccordionSummary expandIcon={<ExpandMoreRoundedIcon />}>
                  <Typography variant="subtitle1" fontWeight={600}>
                    {faq.question}
                  </Typography>
                </AccordionSummary>
                <AccordionDetails>
                  <Typography variant="body2" color="text.secondary">
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
  icon: React.ReactNode;
  title: string;
  caption: string;
  onClick: () => void;
  tone?: 'primary' | 'danger';
}) {
  const isDanger = tone === 'danger';
  return (
    <Card sx={{ flex: 1 }}>
      <CardActionArea onClick={onClick} sx={{ height: '100%' }} aria-label={title}>
        <CardContent>
          <Box
            sx={{
              width: 40,
              height: 40,
              borderRadius: 2,
              display: 'grid',
              placeItems: 'center',
              mb: 1.25,
              bgcolor: isDanger ? brand.redSoft : brand.accentSoft,
              color: isDanger ? 'error.main' : 'primary.main',
            }}
          >
            {icon}
          </Box>
          <Typography variant="subtitle1" fontWeight={700} lineHeight={1.3}>
            {title}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {caption}
          </Typography>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}
