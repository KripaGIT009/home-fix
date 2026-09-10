import { useMemo } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, MenuItem, Stack, TextField } from '@mui/material';
import { isApiError } from '@api/client';
import { formatCurrency } from '@lib/format';
import { useRequestSettlement } from './hooks';
import { buildSettlementSchema, type SettlementFormValues } from './schemas';
import type { SettlementInfo } from './api';

interface SettlementFormProps {
  info: SettlementInfo;
}

/**
 * Settlement request form (Requirement 14.2): validates that the amount is
 * between 1.00 and the available balance and that a verified bank account is
 * selected before submitting. If no verified bank account exists, the form is
 * disabled with an explanatory notice (Requirement 14.2b).
 */
export function SettlementForm({ info }: SettlementFormProps) {
  const verifiedAccounts = useMemo(
    () => info.bankAccounts.filter((account) => account.verified),
    [info.bankAccounts],
  );
  const hasVerifiedAccount = verifiedAccounts.length > 0;
  const canRequest = hasVerifiedAccount && info.availableBalance >= 1;

  const requestSettlement = useRequestSettlement();

  const schema = useMemo(
    () => buildSettlementSchema(info.availableBalance),
    [info.availableBalance],
  );

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SettlementFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      amount: undefined as unknown as number,
      bankAccountId: verifiedAccounts[0]?.id ?? '',
    },
    mode: 'onSubmit',
  });

  const onSubmit = (values: SettlementFormValues) => {
    requestSettlement.mutate(values, {
      onSuccess: () =>
        reset({ amount: undefined as unknown as number, bankAccountId: values.bankAccountId }),
    });
  };

  if (!hasVerifiedAccount) {
    return (
      <Alert severity="warning">
        Add and verify a bank account in your profile before requesting a settlement.
      </Alert>
    );
  }

  if (info.availableBalance < 1) {
    return (
      <Alert severity="info">
        You need an available balance of at least {formatCurrency(1, info.currency)} to request a
        settlement.
      </Alert>
    );
  }

  return (
    <Stack
      component="form"
      spacing={1.5}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      <TextField
        label="Amount"
        type="number"
        fullWidth
        size="small"
        inputProps={{ min: 1, max: info.availableBalance, step: 0.01, inputMode: 'decimal' }}
        error={Boolean(errors.amount)}
        helperText={
          errors.amount?.message ??
          `Available: ${formatCurrency(info.availableBalance, info.currency)}`
        }
        {...register('amount')}
      />

      <Controller
        control={control}
        name="bankAccountId"
        render={({ field }) => (
          <TextField
            select
            label="Deposit to"
            fullWidth
            size="small"
            error={Boolean(errors.bankAccountId)}
            helperText={errors.bankAccountId?.message ?? ' '}
            {...field}
          >
            {verifiedAccounts.map((account) => (
              <MenuItem key={account.id} value={account.id}>
                {account.label}
              </MenuItem>
            ))}
          </TextField>
        )}
      />

      {requestSettlement.isError ? (
        <Alert severity="error">
          {isApiError(requestSettlement.error)
            ? requestSettlement.error.message
            : 'Could not submit the settlement request.'}
        </Alert>
      ) : null}
      {requestSettlement.isSuccess ? (
        <Alert severity="success">Settlement request submitted.</Alert>
      ) : null}

      <Button
        type="submit"
        variant="contained"
        disabled={!canRequest || requestSettlement.isPending}
      >
        {requestSettlement.isPending ? 'Submitting…' : 'Request settlement'}
      </Button>
    </Stack>
  );
}
