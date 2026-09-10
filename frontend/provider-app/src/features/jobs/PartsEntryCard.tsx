import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Divider,
  List,
  ListItem,
  ListItemText,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import { isApiError } from '@api/client';
import { formatCurrency } from '@lib/format';
import type { PartLineItem } from './api';
import { useAddJobPart } from './hooks';
import { partSchema, type PartFormValues } from './schemas';

interface PartsEntryCardProps {
  bookingId: string;
  parts: PartLineItem[];
  currency: string;
  /** Whether new parts can be added (only while the job is being executed). */
  disabled?: boolean;
}

/**
 * Parts/materials entry (Requirement 11.3): item name, quantity (min 1), and
 * unit cost (min 0.01). Adding a line item submits it to the Booking Service,
 * which recalculates the price via the Pricing Engine.
 */
export function PartsEntryCard({
  bookingId,
  parts,
  currency,
  disabled = false,
}: PartsEntryCardProps) {
  const addPart = useAddJobPart(bookingId);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<PartFormValues>({
    resolver: zodResolver(partSchema),
    defaultValues: { itemName: '', quantity: 1, unitCost: 0.01 },
    mode: 'onSubmit',
  });

  const onSubmit = (values: PartFormValues) => {
    addPart.mutate(values, {
      onSuccess: () => reset({ itemName: '', quantity: 1, unitCost: 0.01 }),
    });
  };

  const partsTotal = parts.reduce((sum, part) => sum + part.quantity * part.unitCost, 0);

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle2" fontWeight={700}>
          Parts &amp; materials
        </Typography>

        {parts.length > 0 ? (
          <>
            <List dense disablePadding sx={{ mt: 1 }}>
              {parts.map((part) => (
                <ListItem
                  key={part.id}
                  disableGutters
                  secondaryAction={
                    <Typography variant="body2" fontWeight={700}>
                      {formatCurrency(part.quantity * part.unitCost, currency)}
                    </Typography>
                  }
                >
                  <ListItemText
                    primary={part.itemName}
                    secondary={`${part.quantity} × ${formatCurrency(part.unitCost, currency)}`}
                  />
                </ListItem>
              ))}
            </List>
            <Divider sx={{ my: 1 }} />
            <Stack direction="row" justifyContent="space-between">
              <Typography variant="body2" color="text.secondary">
                Parts total
              </Typography>
              <Typography variant="subtitle2" fontWeight={700}>
                {formatCurrency(partsTotal, currency)}
              </Typography>
            </Stack>
          </>
        ) : (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            No parts recorded yet.
          </Typography>
        )}

        {!disabled ? (
          <Stack
            component="form"
            spacing={1.5}
            sx={{ mt: 2 }}
            onSubmit={(event) => void handleSubmit(onSubmit)(event)}
            noValidate
          >
            <TextField
              label="Item name"
              fullWidth
              size="small"
              error={Boolean(errors.itemName)}
              helperText={errors.itemName?.message ?? ' '}
              {...register('itemName')}
            />
            <Stack direction="row" spacing={1.5}>
              <TextField
                label="Quantity"
                type="number"
                size="small"
                fullWidth
                inputProps={{ min: 1, step: 1, inputMode: 'numeric' }}
                error={Boolean(errors.quantity)}
                helperText={errors.quantity?.message ?? ' '}
                {...register('quantity')}
              />
              <TextField
                label="Unit cost"
                type="number"
                size="small"
                fullWidth
                inputProps={{ min: 0.01, step: 0.01, inputMode: 'decimal' }}
                error={Boolean(errors.unitCost)}
                helperText={errors.unitCost?.message ?? ' '}
                {...register('unitCost')}
              />
            </Stack>
            {addPart.isError ? (
              <Alert severity="error">
                {isApiError(addPart.error) ? addPart.error.message : 'Could not add the item.'}
              </Alert>
            ) : null}
            <Button
              type="submit"
              variant="outlined"
              startIcon={<AddRoundedIcon />}
              disabled={addPart.isPending}
            >
              {addPart.isPending ? 'Adding…' : 'Add item'}
            </Button>
          </Stack>
        ) : null}
      </CardContent>
    </Card>
  );
}
