import { Button, Divider, Stack } from '@mui/material';
import GoogleIcon from '@mui/icons-material/Google';
import AppleIcon from '@mui/icons-material/Apple';
import type { SocialProvider } from './api';

interface SocialLoginButtonsProps {
  onSelect: (provider: SocialProvider) => void;
  disabled?: boolean;
}

/**
 * Social login buttons for Google and Apple (Requirement 1.5).
 *
 * Acquiring the provider identity token requires the provider SDK, which is
 * wired up separately; this component reports the chosen provider to its
 * parent, which drives the social-login mutation.
 */
export function SocialLoginButtons({ onSelect, disabled = false }: SocialLoginButtonsProps) {
  return (
    <Stack spacing={2}>
      <Divider>or continue with</Divider>
      <Button
        variant="outlined"
        color="inherit"
        size="large"
        fullWidth
        startIcon={<GoogleIcon />}
        disabled={disabled}
        onClick={() => onSelect('GOOGLE')}
      >
        Continue with Google
      </Button>
      <Button
        variant="outlined"
        color="inherit"
        size="large"
        fullWidth
        startIcon={<AppleIcon />}
        disabled={disabled}
        onClick={() => onSelect('APPLE')}
      >
        Continue with Apple
      </Button>
    </Stack>
  );
}
