import { Button, Divider, Stack, Tooltip, Typography } from '@mui/material';
import GoogleIcon from '@mui/icons-material/Google';
import AppleIcon from '@mui/icons-material/Apple';
import FacebookIcon from '@mui/icons-material/Facebook';
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
 * a button with no SDK behind it can only ever fail. Unconfigured providers stay
 * visibly disabled and say what is missing rather than erroring on tap.
 */
export function SocialLoginButtons({ onSelect, disabled = false }: SocialLoginButtonsProps) {
  const googleReady = isGoogleSignInConfigured();

  const providers = [
    {
      id: 'GOOGLE' as const,
      label: 'Google',
      icon: <GoogleIcon />,
      color: '#DB4437',
      ready: googleReady,
      unavailableReason: 'Set VITE_GOOGLE_CLIENT_ID to enable Google sign-in',
    },
    {
      id: 'APPLE' as const,
      label: 'Apple',
      icon: <AppleIcon />,
      color: '#111827',
      ready: false,
      unavailableReason: 'Apple sign-in is not configured in this build',
    },
    {
      id: null,
      label: 'Facebook',
      icon: <FacebookIcon />,
      color: '#1877F2',
      ready: false,
      unavailableReason: 'Facebook sign-in is not supported',
    },
  ];

  return (
    <Stack spacing={2}>
      <Divider>
        <Typography variant="caption" color="text.secondary">
          or continue with
        </Typography>
      </Divider>

      <Stack direction="row" spacing={1.5}>
        {providers.map((provider) => {
          const isDisabled = disabled || !provider.ready || provider.id === null;
          const button = (
            <Button
              variant="outlined"
              color="inherit"
              size="large"
              fullWidth
              disabled={isDisabled}
              onClick={() => provider.id && onSelect(provider.id)}
              aria-label={
                provider.ready
                  ? `Continue with ${provider.label}`
                  : `${provider.label} — ${provider.unavailableReason}`
              }
              sx={{ py: 1.25, color: provider.color, borderColor: 'divider' }}
            >
              {provider.icon}
            </Button>
          );

          return (
            <Tooltip
              key={provider.label}
              title={
                provider.ready ? `Continue with ${provider.label}` : provider.unavailableReason
              }
            >
              {/* A disabled button fires no events, so the tooltip needs a live wrapper. */}
              <span style={{ flex: 1, display: 'flex' }}>{button}</span>
            </Tooltip>
          );
        })}
      </Stack>

      {googleReady ? null : (
        <Typography variant="caption" color="text.secondary" textAlign="center">
          Social sign-in needs a provider client id. Use your mobile number to continue.
        </Typography>
      )}
    </Stack>
  );
}
