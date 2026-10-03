import { useEffect, useMemo, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Box, Button, Container, Stack } from '@mui/material';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import SearchOffRoundedIcon from '@mui/icons-material/SearchOffRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { ServiceSearchField } from '@components/ServiceSearchField';
import { EmptyState } from '@components/StateViews';
import { formatDuration } from '@lib/format';
import { layout } from '@lib/theme';
import { HeroSection } from './HeroSection';
import { HowItWorks } from './HowItWorks';
import { SectionHeading } from './SectionHeading';
import { ServiceRail } from './ServiceRail';
import { ServiceTile } from './ServiceTile';
import { SpotlightCarousel } from './SpotlightCarousel';
import { useCategories } from './hooks';
import {
  cheapestPerCategory,
  featuredServices,
  flattenServices,
  matchesQuery,
  pluralise,
  QUICK_FIX_MAX_MINUTES,
  quickFixes,
  type ServiceMatch,
} from './storefront';

/**
 * Home screen (Requirement 28.7): the customer's storefront — category
 * shortcuts and a mosaic of services, catalog-driven promo banners, a
 * quick-fixes rail, one rail per category, and how booking works.
 *
 * Search (`?q=`, typed in the header or, on mobile, the field at the top) and
 * the 24×7 view (`?view=emergency`) live in the URL and swap the storefront
 * for a results grid. Both filter the already-cached catalog on the client
 * rather than issuing a request per keystroke; the catalog is small, changes
 * rarely, and is cached for 5 minutes (see useCategories).
 */
export function HomeScreen() {
  const [params, setParams] = useSearchParams();
  const { data: categories, isLoading, isError, error, refetch } = useCategories();

  const query = params.get('q') ?? '';
  const isSearching = query.trim().length > 0;

  const allServices = useMemo(() => flattenServices(categories), [categories]);
  const searchResults = useMemo(
    () => (isSearching ? allServices.filter((match) => matchesQuery(query, match)) : []),
    [allServices, isSearching, query],
  );
  const emergencyServices = useMemo(
    () => allServices.filter((match) => match.subcategory.emergencyAvailable),
    [allServices],
  );
  const quick = useMemo(() => quickFixes(allServices), [allServices]);
  const cheapest = useMemo(() => cheapestPerCategory(categories), [categories]);
  const featured = useMemo(() => featuredServices(categories, 4), [categories]);

  // Search always wins over the 24×7 view: typing is the more recent intent.
  const showEmergency =
    !isSearching && params.get('view') === 'emergency' && emergencyServices.length > 0;
  const mode = isSearching ? 'search' : showEmergency ? 'emergency' : 'storefront';

  // A results grid replaces everything above wherever the customer had
  // scrolled to, so each switch between storefront and results starts at the top.
  const previousMode = useRef(mode);
  useEffect(() => {
    if (previousMode.current !== mode) window.scrollTo({ top: 0 });
    previousMode.current = mode;
  }, [mode]);

  // Pushed, not replaced, so Back returns to the storefront.
  const openEmergency = () => setParams({ view: 'emergency' });
  const showStorefront = () => setParams({});
  const scrollToQuickFixes = () =>
    document.getElementById('quick-fixes')?.scrollIntoView({ behavior: 'smooth', block: 'start' });

  return (
    <AppShell width="bleed" footer plain>
      <Container
        maxWidth={false}
        sx={{ maxWidth: layout.maxWidth, px: layout.gutter, pt: { xs: 2, md: 5 } }}
      >
        {/* Mobile has no room for search in the bar, so it leads the page. */}
        <Box sx={{ display: { md: 'none' }, mb: 3 }}>
          <ServiceSearchField variant="page" />
        </Box>

        <QueryStateView
          isLoading={isLoading}
          isError={isError}
          error={error}
          onRetry={() => void refetch()}
          isEmpty={!categories || categories.length === 0}
          emptyTitle="No services yet"
          emptyMessage="No services are available in your area right now. Please check back soon."
          skeletonRows={2}
          skeletonHeight={140}
        >
          {mode !== 'storefront' ? (
            <ResultsGrid
              title={
                isSearching
                  ? `${pluralise(searchResults.length, 'service')} for “${query.trim()}”`
                  : 'Emergency help, 24×7'
              }
              caption={
                isSearching ? undefined : 'Services you can book at any hour of the day or night.'
              }
              clearLabel={isSearching ? 'Clear search' : 'All services'}
              onClear={showStorefront}
              results={isSearching ? searchResults : emergencyServices}
            />
          ) : (
            <Stack spacing={{ xs: 5, md: 7 }}>
              <HeroSection
                categories={categories ?? []}
                featured={featured}
                emergencyCount={emergencyServices.length}
                onEmergency={openEmergency}
              />

              <SpotlightCarousel
                cheapest={cheapest}
                emergencyCount={emergencyServices.length}
                quickFixes={quick}
                onEmergency={openEmergency}
                onQuickFixes={scrollToQuickFixes}
              />

              {quick.length > 0 ? (
                <ServiceRail
                  id="quick-fixes"
                  title="Quick fixes"
                  caption={`Visits of ${formatDuration(QUICK_FIX_MAX_MINUTES)} or less`}
                  services={quick}
                />
              ) : null}

              {/*
               * Two categories side by side on wide screens: each holds only a
               * couple of services today, and a full-width row of two cards
               * would be mostly empty. Rails still scroll as categories grow.
               */}
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0, 1fr))' },
                  columnGap: 6,
                  rowGap: { xs: 5, md: 7 },
                }}
              >
                {categories?.map((category) =>
                  category.subcategories?.length ? (
                    <ServiceRail
                      key={category.id}
                      id={`category-${category.id}`}
                      title={category.name}
                      caption={category.description}
                      seeAllTo={`/categories/${category.id}`}
                      seeAllLabel={`See all ${category.name} services`}
                      services={category.subcategories.map((subcategory) => ({
                        category,
                        subcategory,
                      }))}
                    />
                  ) : null,
                )}
              </Box>

              <HowItWorks />
            </Stack>
          )}
        </QueryStateView>
      </Container>
    </AppShell>
  );
}

/** Search results or the 24×7 list: a heading with a way back, then a card grid. */
function ResultsGrid({
  title,
  caption,
  clearLabel,
  onClear,
  results,
}: {
  title: string;
  caption: string | undefined;
  clearLabel: string;
  onClear: () => void;
  results: ServiceMatch[];
}) {
  return (
    <Box component="section" aria-labelledby="results-title" aria-live="polite">
      <SectionHeading
        id="results-title"
        title={title}
        caption={caption}
        action={
          <Button size="small" onClick={onClear} startIcon={<CloseRoundedIcon />}>
            {clearLabel}
          </Button>
        }
      />
      {results.length === 0 ? (
        <EmptyState
          compact
          icon={<SearchOffRoundedIcon />}
          title="No services match that search"
          description="Try a broader term, like “plumbing” or “cleaning”."
        />
      ) : (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: {
              xs: 'repeat(2, minmax(0, 1fr))',
              sm: 'repeat(3, minmax(0, 1fr))',
              md: 'repeat(5, minmax(0, 1fr))',
            },
            columnGap: { xs: 1.5, md: 2 },
            rowGap: { xs: 3, md: 3.5 },
          }}
        >
          {results.map(({ category, subcategory }) => (
            <ServiceTile key={subcategory.id} category={category} subcategory={subcategory} />
          ))}
        </Box>
      )}
    </Box>
  );
}
