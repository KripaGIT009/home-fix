import { Alert, Box } from '@mui/material';
import { env } from '@config/env';

/**
 * Local builds only: the local stack sends no email, so say where the emailed
 * codes land instead. The counterpart of the OTP step's dev SMS note.
 */
export function DevMailHint() {
  if (env.isProduction) return null;

  return (
    <Alert severity="info" sx={{ fontSize: '0.8125rem' }}>
      Local dev sends no email. Read the code from the dev mail log:
      <Box component="code" sx={{ display: 'block', mt: 0.5, wordBreak: 'break-all' }}>
        docker/dev-mail/dev-mail.log
      </Box>
    </Alert>
  );
}
