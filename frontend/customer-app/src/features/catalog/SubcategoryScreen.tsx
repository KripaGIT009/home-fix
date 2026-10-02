import { useNavigate, useParams } from 'react-router-dom';
import { Box, Stack, Typography } from '@mui/material';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { IconTile } from '@components/StateViews';
import { CategoryIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import { useCategory, useSubcategories } from './hooks';
import { ServiceCard } from './ServiceCard';

/**
 * Subcategory selection screen (Requirement 28.7). Lists the active
 * subcategories under the chosen category, each with its starting price,
 * typical duration and a 24×7 badge where emergency booking is available
 * (Requirement 3.7). Selecting one opens the Service Request screen.
 */
export function SubcategoryScreen() {
  const { categoryId = '' } = useParams();
  const navigate = useNavigate();

  const categoryQuery = useCategory(categoryId);
  const subcategoriesQuery = useSubcategories(categoryId);

  const category = categoryQuery.data;
  const categoryName = category?.name ?? 'Services';
  const art = categoryArt(category?.icon, category?.name);
  const count = subcategoriesQuery.data?.length ?? 0;

  return (
    <AppShell title={categoryName} onBack={() => navigate('/home')} width="full">
      <Stack spacing={{ xs: 2.5, md: 4 }}>
        <Stack
          direction="row"
          spacing={2}
          alignItems="center"
          sx={{
            p: { xs: 2, md: 3 },
            borderRadius: '20px',
            bgcolor: art.wash,
          }}
        >
          <IconTile size={56} bg="#FFFFFF" color={art.accent}>
            {category ? (
              <CategoryIcon iconKey={category.icon} categoryName={category.name} />
            ) : (
              <HandymanRoundedIcon />
            )}
          </IconTile>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="h4" component="h2">
              Choose a {categoryName.toLowerCase()} service
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
              {category?.description ??
                (count > 0
                  ? `${count} service${count === 1 ? '' : 's'} · verified pros · price shown before you book`
                  : 'Verified pros, with the price shown before you book.')}
            </Typography>
          </Box>
        </Stack>

        <QueryStateView
          isLoading={subcategoriesQuery.isLoading}
          isError={subcategoriesQuery.isError}
          error={subcategoriesQuery.error}
          onRetry={() => void subcategoriesQuery.refetch()}
          isEmpty={!subcategoriesQuery.data || subcategoriesQuery.data.length === 0}
          emptyTitle="Nothing to book here yet"
          emptyMessage="No services are available in this category right now."
          skeletonHeight={150}
        >
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, minmax(0, 1fr))',
                lg: 'repeat(3, minmax(0, 1fr))',
              },
              gap: { xs: 1.5, md: 2.5 },
            }}
          >
            {subcategoriesQuery.data?.map((subcategory) => (
              <ServiceCard
                key={subcategory.id}
                category={category}
                subcategory={subcategory}
                showCategory={false}
                onSelect={() => navigate(`/book/${subcategory.id}`)}
              />
            ))}
          </Box>
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}
