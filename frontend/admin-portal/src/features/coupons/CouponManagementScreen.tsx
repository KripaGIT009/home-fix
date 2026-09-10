import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  MenuItem,
  TextField,
} from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatCurrency, formatDate, formatNumber } from '@lib/format';
import { useCoupons, useCreateCoupon, useDeactivateCoupon } from './hooks';
import { couponSchema, type CouponFormValues } from './schemas';
import type { Coupon, CreateCouponPayload } from './api';

/**
 * Coupon Management module (Requirement 19.2, Requirement 21). Lists coupons
 * with their redemption progress and status, supports creating a coupon with
 * client-side validation mirroring the server constraints (Req 21.1), and lets
 * an admin deactivate an active coupon (Req 21.4).
 */
export function CouponManagementScreen() {
  const couponsQuery = useCoupons();
  const deactivate = useDeactivateCoupon();
  const [creating, setCreating] = useState(false);

  const columns: Column<Coupon>[] = [
    { key: 'code', header: 'Code', render: (row) => row.code },
    {
      key: 'discount',
      header: 'Discount',
      render: (row) =>
        row.discountType === 'PERCENTAGE'
          ? `${row.discountValue}%${row.maxDiscountCap ? ` (max ${formatCurrency(row.maxDiscountCap)})` : ''}`
          : formatCurrency(row.discountValue),
    },
    {
      key: 'minOrder',
      header: 'Min order',
      align: 'right',
      render: (row) => formatCurrency(row.minOrderValue),
    },
    {
      key: 'validity',
      header: 'Valid',
      render: (row) => `${formatDate(row.validFrom)} → ${formatDate(row.expiryDate)}`,
    },
    {
      key: 'usage',
      header: 'Redeemed',
      align: 'right',
      render: (row) => `${formatNumber(row.totalRedeemed)} / ${formatNumber(row.totalLimit)}`,
    },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) =>
        row.status === 'ACTIVE' ? (
          <Button
            size="small"
            color="error"
            variant="outlined"
            disabled={deactivate.isPending}
            onClick={() => deactivate.mutate(row.id)}
          >
            Deactivate
          </Button>
        ) : null,
    },
  ];

  return (
    <ModuleScreen
      title="Coupon Management"
      description="Create and manage promotional coupon codes."
      actions={
        <Button
          variant="contained"
          startIcon={<AddRoundedIcon />}
          onClick={() => setCreating(true)}
        >
          New coupon
        </Button>
      }
    >
      {deactivate.isError ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {deactivate.error.message}
        </Alert>
      ) : null}

      <QueryStateView
        isLoading={couponsQuery.isLoading}
        isError={couponsQuery.isError}
        error={couponsQuery.error}
        onRetry={() => void couponsQuery.refetch()}
        isEmpty={(couponsQuery.data?.length ?? 0) === 0}
        emptyMessage="No coupons created yet."
      >
        <DataTable columns={columns} rows={couponsQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {creating ? <CreateCouponDialog onClose={() => setCreating(false)} /> : null}
    </ModuleScreen>
  );
}

interface CreateCouponDialogProps {
  onClose: () => void;
}

function CreateCouponDialog({ onClose }: CreateCouponDialogProps) {
  const create = useCreateCoupon();
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<CouponFormValues>({
    resolver: zodResolver(couponSchema),
    mode: 'onBlur',
    defaultValues: {
      code: '',
      discountType: 'FLAT',
      minOrderValue: 0,
      perUserLimit: 1,
      totalLimit: 1,
    },
  });

  const discountType = watch('discountType');

  const onSubmit = (values: CouponFormValues) => {
    const payload: CreateCouponPayload = {
      code: values.code.trim().toUpperCase(),
      discountType: values.discountType,
      discountValue: values.discountValue,
      minOrderValue: values.minOrderValue,
      validFrom: values.validFrom,
      expiryDate: values.expiryDate,
      perUserLimit: values.perUserLimit,
      totalLimit: values.totalLimit,
      ...(values.discountType === 'PERCENTAGE' && values.maxDiscountCap !== undefined
        ? { maxDiscountCap: values.maxDiscountCap }
        : {}),
    };
    create.mutate(payload, { onSuccess: onClose });
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Create coupon</DialogTitle>
      <form
        onSubmit={(event) => {
          void handleSubmit(onSubmit)(event);
        }}
        noValidate
      >
        <DialogContent dividers>
          <Grid container spacing={2}>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Code"
                fullWidth
                {...register('code')}
                error={Boolean(errors.code)}
                helperText={errors.code?.message}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Discount type"
                select
                fullWidth
                defaultValue="FLAT"
                {...register('discountType')}
                error={Boolean(errors.discountType)}
                helperText={errors.discountType?.message}
              >
                <MenuItem value="FLAT">Flat</MenuItem>
                <MenuItem value="PERCENTAGE">Percentage</MenuItem>
              </TextField>
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label={discountType === 'PERCENTAGE' ? 'Discount (%)' : 'Discount amount'}
                type="number"
                fullWidth
                inputProps={{ min: 0, step: 0.01 }}
                {...register('discountValue')}
                error={Boolean(errors.discountValue)}
                helperText={errors.discountValue?.message}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Max discount cap"
                type="number"
                fullWidth
                disabled={discountType !== 'PERCENTAGE'}
                inputProps={{ min: 0, step: 0.01 }}
                {...register('maxDiscountCap')}
                error={Boolean(errors.maxDiscountCap)}
                helperText={
                  errors.maxDiscountCap?.message ??
                  (discountType === 'PERCENTAGE' ? 'Required for percentage coupons.' : undefined)
                }
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Minimum order value"
                type="number"
                fullWidth
                inputProps={{ min: 0, step: 0.01 }}
                {...register('minOrderValue')}
                error={Boolean(errors.minOrderValue)}
                helperText={errors.minOrderValue?.message}
              />
            </Grid>
            <Grid item xs={12} sm={6} />
            <Grid item xs={12} sm={6}>
              <TextField
                label="Valid from"
                type="date"
                fullWidth
                InputLabelProps={{ shrink: true }}
                {...register('validFrom')}
                error={Boolean(errors.validFrom)}
                helperText={errors.validFrom?.message}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Expiry date"
                type="date"
                fullWidth
                InputLabelProps={{ shrink: true }}
                {...register('expiryDate')}
                error={Boolean(errors.expiryDate)}
                helperText={errors.expiryDate?.message}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Per-user limit"
                type="number"
                fullWidth
                inputProps={{ min: 1, step: 1 }}
                {...register('perUserLimit')}
                error={Boolean(errors.perUserLimit)}
                helperText={errors.perUserLimit?.message}
              />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField
                label="Total limit"
                type="number"
                fullWidth
                inputProps={{ min: 1, step: 1 }}
                {...register('totalLimit')}
                error={Boolean(errors.totalLimit)}
                helperText={errors.totalLimit?.message}
              />
            </Grid>
          </Grid>
          {create.isError ? (
            <Alert severity="error" sx={{ mt: 2 }}>
              {create.error.message}
            </Alert>
          ) : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={create.isPending}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create coupon'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
