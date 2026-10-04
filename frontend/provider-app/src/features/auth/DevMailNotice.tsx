import { Alert, Box } from '@mui/material';
import { env } from '@config/env';

/**
 * Local-dev hint next to an emailed-code field, the counterpart of the OTP
 * step's SMS hint: with no email provider configured the Auth Service appends
 * every email to the dev mail log instead.
 */
export function DevMailNotice() {
  if (env.isProduction) {
    return null;
  }

  return (
    <Alert severity="info" sx={{ fontSize: '0.75rem' }}>
      Local dev sends no email. Read the code from the dev mail log:
      <Box component="code" sx={{ display: 'block', mt: 0.5, wordBreak: 'break-all' }}>
        docker/dev-mail/dev-mail.log
      </Box>
    </Alert>
  );
}
