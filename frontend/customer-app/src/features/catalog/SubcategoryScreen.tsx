import { useNavigate, useParams } from 'react-router-dom';
import { Box, Card, CardActionArea, Chip, Stack, Typography } from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency } from '@lib/format';
import { CategoryIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import { useCategory, useSubcategories } from './hooks';

/**
 * Subcategory selection screen (Requirement 28.7). Lists the active
 * subcategories under the chosen category, each with its starting price,
 * typical duration and an emergency badge where available (Requirement 3.7).
 * Selecting one opens the Service Request screen.
 */
export function SubcategoryScreen() {
  const { categoryId = '' } = useParams();
  const navigate = useNavigate();

  const categoryQuery = useCategory(categoryId);
  const subcategoriesQuery = useSubcategories(categoryId);

  const category = categoryQuery.data;
  const categoryName = category?.name ?? 'Services';
  const art = categoryArt(category?.icon, category?.name);

  return (
    <AppShell title={categoryName} onBack={() => navigate('/home')}>
      <Stack spacing={2}>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <Box
            sx={{
              width: 48,
              height: 48,
              borderRadius: 2.5,
              display: 'grid',
              placeItems: 'center',
              bgcolor: art.wash,
              color: art.accent,
              flexShrink: 0,
            }}
          >
            <CategoryIcon
              iconKey={category?.icon}
              categoryName={category?.name}
              sx={{ fontSize: 26 }}
              aria-hidden
            />
          </Box>
          <Box>
            <Typography variant="h5" component="h1">
              Choose a service
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {category?.description ?? `All ${categoryName.toLowerCase()} services near you.`}
            </Typography>
          </Box>
        </Stack>

        <QueryStateView
          isLoading={subcategoriesQuery.isLoading}
          isError={subcategoriesQuery.isError}
          error={subcategoriesQuery.error}
          onRetry={() => void subcategoriesQuery.refetch()}
          isEmpty={!subcategoriesQuery.data || subcategoriesQuery.data.length === 0}
          emptyMessage="No services are available in this category right now."
        >
          <Stack spacing={1.25}>
            {subcategoriesQuery.data?.map((subcategory) => (
              <Card key={subcategory.id}>
                <CardActionArea
                  onClick={() => navigate(`/book/${subcategory.id}`)}
                  sx={{ p: 2 }}
                  aria-label={`Book ${subcategory.name}`}
                >
                  <Stack direction="row" spacing={1.5} alignItems="center">
                    <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                      <Stack direction="row" spacing={0.75} alignItems="center" sx={{ mb: 0.25 }}>
                        <Typography variant="subtitle1" fontWeight={600}>
                          {subcategory.name}
                        </Typography>
                        {subcategory.emergencyAvailable ? (
                          <Chip
                            size="small"
                            color="error"
                            icon={<BoltRoundedIcon sx={{ fontSize: 14 }} />}
                            label="24×7"
                          />
                        ) : null}
                      </Stack>

                      {subcategory.description ? (
                        <Typography variant="body2" color="text.secondary" sx={{ mb: 0.75 }}>
                          {subcategory.description}
                        </Typography>
                      ) : null}

                      <Stack direction="row" spacing={0.5} alignItems="center">
                        <ScheduleRoundedIcon sx={{ fontSize: 15, color: 'text.secondary' }} />
                        <Typography variant="caption" color="text.secondary">
                          about {subcategory.estimatedDurationMin} min
                        </Typography>
                      </Stack>
                    </Box>

                    <Stack alignItems="flex-end" sx={{ flexShrink: 0 }}>
                      <Typography variant="subtitle1" fontWeight={700}>
                        {formatCurrency(subcategory.basePrice)}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        onwards
                      </Typography>
                    </Stack>
                    <ChevronRightRoundedIcon sx={{ color: 'text.secondary' }} />
                  </Stack>
                </CardActionArea>
              </Card>
            ))}
          </Stack>
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}
