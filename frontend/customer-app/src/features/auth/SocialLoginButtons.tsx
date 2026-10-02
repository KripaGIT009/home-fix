import { Button, Divider, Stack, Typography } from '@mui/material';
import GoogleIcon from '@mui/icons-material/Google';
import { isGoogleSignInConfigured } from './googleSignIn';
import type { SocialProvider } from './api';

interface SocialLoginButtonsProps {
  onSelect: (provider: SocialProvider) => void;
  disabled?: boolean;
}

/**
 * Social login buttons (Requirement 1.5).
 *
 * A provider is only offered when this build can actually obtain an identity
 * token for it: the Auth Service verifies a real provider-issued OIDC token, so
 * a button with no SDK behind it could only ever fail. Today that means Google,
 * when VITE_GOOGLE_CLIENT_ID is set; with no provider configured the whole
 * block is omitted rather than shown disabled.
 */
export function SocialLoginButtons({ onSelect, disabled = false }: SocialLoginButtonsProps) {
  const providers = [
    {
      id: 'GOOGLE' as const,
      label: 'Google',
      icon: <GoogleIcon sx={{ color: '#DB4437' }} />,
      ready: isGoogleSignInConfigured(),
    },
  ].filter((provider) => provider.ready);

  if (providers.length === 0) return null;

  return (
    <Stack spacing={2}>
      <Divider>
        <Typography variant="caption" color="text.secondary">
          or
        </Typography>
      </Divider>
      {providers.map((provider) => (
        <Button
          key={provider.id}
          variant="outlined"
          color="inherit"
          size="large"
          fullWidth
          disabled={disabled}
          startIcon={provider.icon}
          onClick={() => onSelect(provider.id)}
        >
          Continue with {provider.label}
        </Button>
      ))}
    </Stack>
  );
}
