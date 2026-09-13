import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  IconButton,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import LoginRoundedIcon from '@mui/icons-material/LoginRounded';
import PersonOutlineRoundedIcon from '@mui/icons-material/PersonOutlineRounded';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined';
import { credentialsSchema, type CredentialsFormValues } from './schemas';

interface PasswordStepProps {
  onSubmit: (values: CredentialsFormValues) => void;
  isSubmitting: boolean;
  errorMessage: string | null;
}

/**
 * Username and password sign-in for staff accounts.
 *
 * A single submit, unlike the OTP flow's two steps. The error surface is
 * deliberately flat: the service answers a wrong username and a wrong password
 * with the same code, and this form shows that one message rather than guessing
 * which field was at fault, so the console does not become a way to discover
 * which usernames exist.
 */
export function PasswordStep({ onSubmit, isSubmitting, errorMessage }: PasswordStepProps) {
  const [showPassword, setShowPassword] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CredentialsFormValues>({
    resolver: zodResolver(credentialsSchema),
    defaultValues: { username: '', password: '' },
    mode: 'onBlur',
  });

  return (
    <Stack
      component="form"
      spacing={2.25}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}

      <Box>
        <Typography variant="subtitle2" sx={{ mb: 0.75, color: 'text.primary' }}>
          Username
        </Typography>
        <TextField
          autoComplete="username"
          fullWidth
          placeholder="admin"
          error={Boolean(errors.username)}
          helperText={errors.username?.message ?? ' '}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <PersonOutlineRoundedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
              </InputAdornment>
            ),
          }}
          inputProps={{ 'aria-label': 'Username', autoCapitalize: 'none', spellCheck: false }}
          {...register('username')}
        />
      </Box>

      <Box>
        <Typography variant="subtitle2" sx={{ mb: 0.75, color: 'text.primary' }}>
          Password
        </Typography>
        <TextField
          type={showPassword ? 'text' : 'password'}
          autoComplete="current-password"
          fullWidth
          placeholder="••••••••"
          error={Boolean(errors.password)}
          helperText={errors.password?.message ?? ' '}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <LockOutlinedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
              </InputAdornment>
            ),
            endAdornment: (
              <InputAdornment position="end">
                <IconButton
                  onClick={() => setShowPassword((visible) => !visible)}
                  edge="end"
                  size="small"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                >
                  {showPassword ? (
                    <VisibilityOffOutlinedIcon fontSize="small" />
                  ) : (
                    <VisibilityOutlinedIcon fontSize="small" />
                  )}
                </IconButton>
              </InputAdornment>
            ),
          }}
          inputProps={{ 'aria-label': 'Password' }}
          {...register('password')}
        />
      </Box>

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={isSubmitting}
        endIcon={isSubmitting ? undefined : <LoginRoundedIcon />}
      >
        {isSubmitting ? 'Signing in…' : 'Sign in'}
      </Button>
    </Stack>
  );
}
