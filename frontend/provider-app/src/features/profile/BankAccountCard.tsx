import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Collapse,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import AccountBalanceRoundedIcon from '@mui/icons-material/AccountBalanceRounded';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import HourglassTopRoundedIcon from '@mui/icons-material/HourglassTopRounded';
import { isApiError } from '@api/client';
import { QueryStateView } from '@components/QueryStateView';
import { useSettlementInfo } from '@features/earnings/hooks';
import { BANK_ACCOUNT_ANCHOR } from './constants';
import { useSaveBankAccount } from './hooks';
import { bankAccountSchema, toBankAccountPayload, type BankAccountFormValues } from './schemas';

const EMPTY_FORM: BankAccountFormValues = {
  accountHolderName: '',
  accountNumber: '',
  confirmAccountNumber: '',
  ifsc: '',
};

/**
 * The bank account settlements are paid into (Requirement 14.2): the masked
 * account and whether it is verified, plus a form to add or replace it. The
 * account is read from settlement info, the same source the Earnings screen's
 * settlement form uses, so saving here refreshes both.
 */
export function BankAccountCard() {
  const location = useLocation();
  const cardRef = useRef<HTMLDivElement>(null);
  const settlementInfo = useSettlementInfo();
  const save = useSaveBankAccount();
  const [editing, setEditing] = useState(false);

  const account = settlementInfo.data?.bankAccounts[0];
  const linkedHere = location.hash === `#${BANK_ACCOUNT_ANCHOR}`;

  // Arriving from the settlement form's link: bring the card into view, and
  // open the form straight away when there is no account yet.
  useEffect(() => {
    if (!linkedHere || !settlementInfo.data) return;
    cardRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    if (settlementInfo.data.bankAccounts.length === 0) setEditing(true);
  }, [linkedHere, settlementInfo.data]);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<BankAccountFormValues>({
    resolver: zodResolver(bankAccountSchema),
    defaultValues: EMPTY_FORM,
    mode: 'onSubmit',
  });

  const openForm = () => {
    save.reset();
    reset(EMPTY_FORM);
    setEditing(true);
  };

  const closeForm = () => {
    reset(EMPTY_FORM);
    setEditing(false);
  };

  const onSubmit = (values: BankAccountFormValues) => {
    save.mutate(toBankAccountPayload(values), { onSuccess: closeForm });
  };

  return (
    <Card ref={cardRef} id={BANK_ACCOUNT_ANCHOR} sx={{ scrollMarginTop: 80 }}>
      <CardContent>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1.5 }}>
          <AccountBalanceRoundedIcon color="primary" aria-hidden />
          <Typography variant="subtitle1" fontWeight={700} component="h2">
            Bank account
          </Typography>
        </Stack>

        <QueryStateView
          isLoading={settlementInfo.isLoading}
          isError={settlementInfo.isError}
          error={settlementInfo.error}
          onRetry={() => void settlementInfo.refetch()}
        >
          {account ? (
            <Box>
              <Stack
                direction="row"
                justifyContent="space-between"
                alignItems="center"
                spacing={1}
                flexWrap="wrap"
                useFlexGap
              >
                <Box sx={{ minWidth: 0 }}>
                  <Typography
                    variant="body1"
                    fontWeight={600}
                    sx={{ fontVariantNumeric: 'tabular-nums' }}
                  >
                    {account.label}
                  </Typography>
                  {account.holderName ? (
                    <Typography variant="body2" color="text.secondary" noWrap>
                      {account.holderName}
                    </Typography>
                  ) : null}
                </Box>
                {account.verified ? (
                  <Chip
                    size="small"
                    color="success"
                    icon={<VerifiedRoundedIcon sx={{ fontSize: 16 }} />}
                    label="Verified"
                  />
                ) : (
                  <Chip
                    size="small"
                    color="warning"
                    icon={<HourglassTopRoundedIcon sx={{ fontSize: 16 }} />}
                    label="Pending verification"
                  />
                )}
              </Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                {account.verified
                  ? 'Settlements you request are paid into this account.'
                  : 'HomeFix checks new accounts before paying into them. You can request settlements once this account is verified.'}
              </Typography>
            </Box>
          ) : (
            <Typography variant="body2" color="text.secondary">
              Add the bank account your settlements should be paid into. HomeFix verifies it before
              the first payout.
            </Typography>
          )}

          {save.isSuccess && !editing ? (
            <Alert severity="success" sx={{ mt: 1.5 }}>
              {save.data.verified
                ? 'Bank account saved and verified.'
                : 'Bank account saved. It will show as verified once HomeFix has checked it.'}
            </Alert>
          ) : null}

          <Collapse in={editing} unmountOnExit>
            <Stack
              component="form"
              spacing={1.5}
              sx={{ mt: 2 }}
              noValidate
              onSubmit={(event) => void handleSubmit(onSubmit)(event)}
            >
              {account ? (
                <Alert severity="info">
                  Saving replaces {account.label}. The new account needs to be verified before
                  settlements can be paid into it.
                </Alert>
              ) : null}
              <TextField
                label="Account holder name"
                fullWidth
                size="small"
                autoComplete="name"
                error={Boolean(errors.accountHolderName)}
                helperText={errors.accountHolderName?.message ?? 'As printed on your passbook'}
                {...register('accountHolderName')}
              />
              <TextField
                label="Account number"
                fullWidth
                size="small"
                autoComplete="off"
                inputProps={{ inputMode: 'numeric', maxLength: 24 }}
                error={Boolean(errors.accountNumber)}
                helperText={errors.accountNumber?.message ?? '9 to 18 digits'}
                {...register('accountNumber')}
              />
              <TextField
                label="Confirm account number"
                fullWidth
                size="small"
                autoComplete="off"
                inputProps={{ inputMode: 'numeric', maxLength: 24 }}
                error={Boolean(errors.confirmAccountNumber)}
                helperText={
                  errors.confirmAccountNumber?.message ?? 'Type it again to rule out typos'
                }
                {...register('confirmAccountNumber')}
              />
              <Controller
                control={control}
                name="ifsc"
                render={({ field }) => (
                  <TextField
                    label="IFSC"
                    fullWidth
                    size="small"
                    autoComplete="off"
                    inputProps={{ maxLength: 11, autoCapitalize: 'characters' }}
                    error={Boolean(errors.ifsc)}
                    helperText={
                      errors.ifsc?.message ?? 'Printed on your cheque book, e.g. HDFC0001234'
                    }
                    {...field}
                    onChange={(event) => field.onChange(event.target.value.toUpperCase())}
                  />
                )}
              />

              {save.isError ? (
                <Alert severity="error">
                  {isApiError(save.error) ? save.error.message : 'Could not save the bank account.'}
                </Alert>
              ) : null}

              <Stack direction="row" spacing={1}>
                <Button
                  type="button"
                  variant="outlined"
                  fullWidth
                  onClick={closeForm}
                  disabled={save.isPending}
                >
                  Cancel
                </Button>
                <Button type="submit" variant="contained" fullWidth disabled={save.isPending}>
                  {save.isPending ? 'Saving…' : 'Save account'}
                </Button>
              </Stack>
            </Stack>
          </Collapse>

          {!editing ? (
            <Button
              variant={account ? 'outlined' : 'contained'}
              fullWidth
              sx={{ mt: 2 }}
              onClick={openForm}
            >
              {account ? 'Replace bank account' : 'Add bank account'}
            </Button>
          ) : null}
        </QueryStateView>
      </CardContent>
    </Card>
  );
}
