import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Button,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { StatusChip } from '@components/StatusChip';
import { useCategories, useUpdateCategoryStatus, useUpdateSubcategoryStatus } from './hooks';
import type { CatalogStatus } from './api';

/** Toggle target: an ACTIVE entry deactivates, otherwise it activates. */
function nextStatus(status: CatalogStatus): CatalogStatus {
  return status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
}

/**
 * Service Category Management module (Requirement 19.2). Presents the catalog
 * as expandable categories, each listing its subcategories. Admins can activate
 * or deactivate a category or an individual subcategory; deactivated entries
 * cannot be booked (Requirement 7.6).
 */
export function CategoryManagementScreen() {
  const categoriesQuery = useCategories();
  const updateCategory = useUpdateCategoryStatus();
  const updateSubcategory = useUpdateSubcategoryStatus();
  const isPending = updateCategory.isPending || updateSubcategory.isPending;

  return (
    <ModuleScreen
      title="Service Categories"
      description="Manage the service catalog. Deactivated entries cannot be booked."
    >
      <QueryStateView
        isLoading={categoriesQuery.isLoading}
        isError={categoriesQuery.isError}
        error={categoriesQuery.error}
        onRetry={() => void categoriesQuery.refetch()}
        isEmpty={(categoriesQuery.data?.length ?? 0) === 0}
        emptyMessage="No service categories configured yet."
      >
        <Stack spacing={1}>
          {(categoriesQuery.data ?? []).map((category) => (
            <Accordion key={category.id} variant="outlined" disableGutters>
              <AccordionSummary expandIcon={<ExpandMoreRoundedIcon />}>
                <Stack
                  direction="row"
                  spacing={2}
                  alignItems="center"
                  justifyContent="space-between"
                  sx={{ width: '100%', pr: 2 }}
                >
                  <Stack direction="row" spacing={1.5} alignItems="center">
                    <Typography fontWeight={600}>{category.name}</Typography>
                    <StatusChip status={category.status} />
                    <Typography variant="caption" color="text.secondary">
                      {category.subcategories.length} subcategories
                    </Typography>
                  </Stack>
                  <Button
                    size="small"
                    variant="outlined"
                    color={category.status === 'ACTIVE' ? 'error' : 'success'}
                    disabled={isPending}
                    component="span"
                    onClick={(event) => {
                      event.stopPropagation();
                      updateCategory.mutate({
                        id: category.id,
                        status: nextStatus(category.status),
                      });
                    }}
                  >
                    {category.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                  </Button>
                </Stack>
              </AccordionSummary>
              <AccordionDetails>
                <List dense disablePadding>
                  {category.subcategories.map((sub) => (
                    <ListItem
                      key={sub.id}
                      secondaryAction={
                        <Button
                          size="small"
                          variant="text"
                          color={sub.status === 'ACTIVE' ? 'error' : 'success'}
                          disabled={isPending}
                          onClick={() =>
                            updateSubcategory.mutate({
                              id: sub.id,
                              status: nextStatus(sub.status),
                            })
                          }
                        >
                          {sub.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                        </Button>
                      }
                    >
                      <ListItemText primary={sub.name} />
                      <StatusChip status={sub.status} />
                    </ListItem>
                  ))}
                </List>
              </AccordionDetails>
            </Accordion>
          ))}
        </Stack>
      </QueryStateView>
    </ModuleScreen>
  );
}
