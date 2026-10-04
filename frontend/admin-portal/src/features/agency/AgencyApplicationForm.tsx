import { useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack } from '@mui/material';
import SendRoundedIcon from '@mui/icons-material/SendRounded';
import { QueryStateView } from '@components/QueryStateView';
import type { CategorySummary } from '@features/categories/api';
import { TENANT_FIELD_ERRORS, tenantErrorMessage } from '@features/tenants/model';
import {
  agencyApplicationSchema,
  toTenantPayload,
  type TenantFormValues,
} from '@features/tenants/schemas';
import { TenantDetailsFields } from '@features/tenants/TenantDetailsFields';
import { useAuthStore } from '@stores/authStore';
import { useActiveCategories, useApplyForAgency } from './hooks';

interface AgencyApplicationFormProps {
  /** Prefills the form, e.g. with a rejected application's name. */
  initialName?: string;
  /** Shown beside Submit, e.g. to leave a re-application. */
  onCancel?: () => void;
}

/**
 * The agency's application (email-auth Requirement 5.1): the same details a
 * platform admin enters for a Tenant, with the contacts required, checked
 * against the same rules (Requirement MT-1.2). The server's rule codes land
 * under the field they name; submitting stores the answer as the account's
 * application, so the page moves on to its status by itself.
 */
export function AgencyApplicationForm({ initialName = '', onCancel }: AgencyApplicationFormProps) {
  const categoriesQuery = useActiveCategories();

  return (
    <QueryStateView
      isLoading={categoriesQuery.isLoading}
      isError={categoriesQuery.isError}
      error={categoriesQuery.error}
      onRetry={() => void categoriesQuery.refetch()}
      isEmpty={(categoriesQuery.data?.length ?? 0) === 0}
      emptyMessage="The service catalog has no active categories yet, so agencies cannot apply right now."
    >
      {categoriesQuery.data ? (
        <ApplicationFields
          categories={categoriesQuery.data}
          initialName={initialName}
          {...(onCancel ? { onCancel } : {})}
        />
      ) : null}
    </QueryStateView>
  );
}

interface ApplicationFieldsProps {
  categories: readonly CategorySummary[];
  initialName: string;
  onCancel?: () => void;
}

/** The form itself, mounted once the categories it validates against are known. */
function ApplicationFields({ categories, initialName, onCancel }: ApplicationFieldsProps) {
  const email = useAuthStore((state) => state.user?.email);
  const apply = useApplyForAgency();

  const activeIds = useMemo(() => new Set(categories.map((c) => c.id)), [categories]);
  const schema = useMemo(() => agencyApplicationSchema(activeIds), [activeIds]);

  const form = useForm<TenantFormValues>({
    resolver: zodResolver(schema),
    mode: 'onBlur',
    defaultValues: {
      name: initialName,
      contactPhone: '',
      contactEmail: email ?? '',
      baseLatitude: '',
      baseLongitude: '',
      serviceRadiusKm: '10',
      categoryIds: [],
    },
  });

  const onSubmit = (values: TenantFormValues) => {
    apply.mutate(toTenantPayload(values), {
      onError: (error) => {
        const field = TENANT_FIELD_ERRORS[error.code];
        if (field) form.setError(field, { message: error.message });
      },
    });
  };

  const fieldError = apply.isError && Boolean(TENANT_FIELD_ERRORS[apply.error.code]);

  return (
    <Box
      component="form"
      noValidate
      onSubmit={(event) => {
        void form.handleSubmit(onSubmit)(event);
      }}
    >
      <TenantDetailsFields form={form} categories={categories} contactRequired />
      {apply.isError && !fieldError ? (
        <Alert severity="error" sx={{ mt: 2 }}>
          {tenantErrorMessage(apply.error)}
        </Alert>
      ) : null}
      <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 3 }}>
        {onCancel ? (
          <Button onClick={onCancel} disabled={apply.isPending}>
            Cancel
          </Button>
        ) : null}
        <Button
          type="submit"
          variant="contained"
          disabled={apply.isPending}
          endIcon={apply.isPending ? undefined : <SendRoundedIcon />}
        >
          {apply.isPending ? 'Submitting…' : 'Submit application'}
        </Button>
      </Stack>
    </Box>
  );
}
