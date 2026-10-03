import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { Box, Button, ButtonBase, Stack, Typography } from '@mui/material';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatDuration, formatPrice } from '@lib/format';
import { brand, radius } from '@lib/theme';
import { ServiceArt } from './ServiceArt';
import { TrustList } from './TrustList';
import { useCategory, useSubcategories } from './hooks';
import { pluralise } from './storefront';
import type { ServiceCategory, ServiceSubcategory } from './api';

/** Clears the sticky app bar when a row is scrolled to or stuck. */
const STICKY_TOP = 88;

const rowId = (subcategory: ServiceSubcategory) => `service-${subcategory.id}`;

/**
 * Subcategory selection screen (Requirement 28.7), laid out like a storefront
 * category page: on desktop a left rail of the category's services that jumps
 * to each one, the services as rows with their starting price, typical
 * duration and 24×7 availability (Requirement 3.7), and HomeFix's promises in
 * a sticky side card. Mobile is a single column. Booking a row opens the
 * Service Request screen.
 */
export function SubcategoryScreen() {
  const { categoryId = '' } = useParams();
  const navigate = useNavigate();

  const categoryQuery = useCategory(categoryId);
  const subcategoriesQuery = useSubcategories(categoryId);

  const category = categoryQuery.data;
  const categoryName = category?.name ?? 'Services';
  const services = subcategoriesQuery.data ?? [];
  const summary = category?.description ?? 'Verified pros, with the price shown before you book.';

  const jumpTo = (subcategory: ServiceSubcategory) => {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    document
      .getElementById(rowId(subcategory))
      ?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
  };

  return (
    <AppShell
      title={categoryName}
      subtitle={summary}
      onBack={() => navigate('/home')}
      width="full"
      plain
      footer
    >
      {/* The mobile bar only fits the title, so the description leads the page. */}
      <Typography variant="body2" color="text.secondary" sx={{ display: { md: 'none' }, mb: 2.5 }}>
        {summary}
        {services.length > 0 ? ` · ${pluralise(services.length, 'service')}` : ''}
      </Typography>

      <QueryStateView
        isLoading={subcategoriesQuery.isLoading}
        isError={subcategoriesQuery.isError}
        error={subcategoriesQuery.error}
        onRetry={() => void subcategoriesQuery.refetch()}
        isEmpty={services.length === 0}
        emptyTitle="Nothing to book here yet"
        emptyMessage="No services are available in this category right now."
        skeletonHeight={150}
      >
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: {
              xs: '1fr',
              md: '220px minmax(0, 1fr)',
              lg: '240px minmax(0, 1fr) 300px',
            },
            columnGap: { md: 3, lg: 4 },
            rowGap: 3,
            alignItems: 'start',
          }}
        >
          <ServiceIndex
            category={category}
            services={services}
            categoryName={categoryName}
            onSelect={jumpTo}
          />

          <Box
            component="ul"
            aria-label={`${categoryName} services`}
            sx={{
              listStyle: 'none',
              m: 0,
              p: 0,
              border: `1px solid ${brand.line}`,
              borderRadius: `${radius.md}px`,
            }}
          >
            {services.map((subcategory, index) => (
              <ServiceRow
                key={subcategory.id}
                category={category}
                subcategory={subcategory}
                divider={index > 0}
              />
            ))}
          </Box>

          <Box
            component="aside"
            aria-labelledby="promise-title"
            sx={{
              gridColumn: { md: '2', lg: 'auto' },
              position: { lg: 'sticky' },
              top: STICKY_TOP,
              p: { xs: 2, md: 2.5 },
              border: `1px solid ${brand.line}`,
              borderRadius: `${radius.md}px`,
            }}
          >
            <Typography
              id="promise-title"
              component="h2"
              sx={{ fontSize: '1rem', fontWeight: 700, mb: 2 }}
            >
              The HomeFix promise
            </Typography>
            <TrustList layout="stack" />
          </Box>
        </Box>
      </QueryStateView>
    </AppShell>
  );
}

/** Desktop-only rail listing the category's services; each jumps to its row. */
function ServiceIndex({
  category,
  services,
  categoryName,
  onSelect,
}: {
  category: ServiceCategory | undefined;
  services: ServiceSubcategory[];
  categoryName: string;
  onSelect: (subcategory: ServiceSubcategory) => void;
}) {
  return (
    <Box
      component="nav"
      aria-label={`${categoryName} services`}
      sx={{
        display: { xs: 'none', md: 'block' },
        position: 'sticky',
        top: STICKY_TOP,
        p: 1.5,
        border: `1px solid ${brand.line}`,
        borderRadius: `${radius.md}px`,
      }}
    >
      <Typography
        component="h2"
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', px: 1, pt: 0.5, pb: 1, fontWeight: 600 }}
      >
        Select a service
      </Typography>
      <Stack spacing={0.5}>
        {services.map((subcategory) => (
          <ButtonBase
            key={subcategory.id}
            onClick={() => onSelect(subcategory)}
            sx={{
              justifyContent: 'flex-start',
              gap: 1.25,
              p: 1,
              textAlign: 'left',
              borderRadius: `${radius.sm}px`,
              '&:hover': { bgcolor: brand.slateSoft },
              '&.Mui-focusVisible': { outline: `2px solid ${brand.accent}`, outlineOffset: 1 },
            }}
          >
            <ServiceArt
              category={category}
              subcategory={subcategory}
              showBadge={false}
              plate={0.66}
              sx={{ width: 48, flexShrink: 0, borderRadius: `${radius.sm}px` }}
            />
            <Typography variant="body2" fontWeight={500} sx={{ lineHeight: 1.35 }}>
              {subcategory.name}
            </Typography>
          </ButtonBase>
        ))}
      </Stack>
    </Box>
  );
}

/** One service: name, duration, "from" price and 24×7 note, with art and a Book button. */
function ServiceRow({
  category,
  subcategory,
  divider,
}: {
  category: ServiceCategory | undefined;
  subcategory: ServiceSubcategory;
  divider: boolean;
}) {
  const titleId = `${rowId(subcategory)}-title`;
  return (
    <Box
      component="li"
      id={rowId(subcategory)}
      aria-labelledby={titleId}
      sx={{
        scrollMarginTop: STICKY_TOP,
        display: 'grid',
        gridTemplateColumns: { xs: 'minmax(0, 1fr) 104px', sm: 'minmax(0, 1fr) 128px' },
        gap: { xs: 2, md: 3 },
        p: { xs: 2, md: 3 },
        borderTop: divider ? `1px solid ${brand.line}` : 'none',
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography
          id={titleId}
          component="h3"
          sx={{ fontSize: { xs: '1rem', md: '1.0625rem' }, fontWeight: 700, lineHeight: 1.35 }}
        >
          {subcategory.name}
        </Typography>
        <Stack
          direction="row"
          spacing={0.5}
          alignItems="center"
          sx={{ mt: 0.75, color: 'text.secondary' }}
        >
          <ScheduleRoundedIcon sx={{ fontSize: 15 }} aria-hidden />
          <Typography variant="body2">
            {formatDuration(subcategory.estimatedDurationMin)}
          </Typography>
        </Stack>
        <Typography variant="body2" sx={{ mt: 0.5 }}>
          <Box component="span" sx={{ color: 'text.secondary' }}>
            from{' '}
          </Box>
          <Box component="span" sx={{ fontWeight: 700 }}>
            {formatPrice(subcategory.basePrice)}
          </Box>
        </Typography>
        {subcategory.emergencyAvailable ? (
          <Stack
            direction="row"
            spacing={0.5}
            alignItems="center"
            sx={{ mt: 1.25, color: brand.warmDark }}
          >
            <BoltRoundedIcon sx={{ fontSize: 15, color: brand.warm }} aria-hidden />
            <Typography variant="caption" fontWeight={600}>
              Emergency booking, 24×7
            </Typography>
          </Stack>
        ) : null}
        {subcategory.description ? (
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ mt: 1.25, pt: 1.25, borderTop: `1px dashed ${brand.line}` }}
          >
            {subcategory.description}
          </Typography>
        ) : null}
      </Box>

      <Box sx={{ position: 'relative', pb: 2 }}>
        <ServiceArt category={category} subcategory={subcategory} showBadge={false} />
        <Button
          component={RouterLink}
          to={`/book/${subcategory.id}`}
          variant="outlined"
          size="small"
          aria-label={`Book ${subcategory.name}, from ${formatPrice(subcategory.basePrice)}`}
          sx={{
            position: 'absolute',
            left: '50%',
            bottom: 0,
            transform: 'translateX(-50%)',
            minWidth: 84,
            borderColor: brand.accentLine,
            color: 'primary.main',
            bgcolor: 'background.paper',
            fontWeight: 700,
            boxShadow: '0 2px 6px -2px rgba(15, 15, 15, 0.12)',
            '&:hover': { bgcolor: brand.accentSoft, borderColor: 'primary.main' },
          }}
        >
          Book
        </Button>
      </Box>
    </Box>
  );
}
