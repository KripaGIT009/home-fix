import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Card,
  CardActionArea,
  Chip,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import PaymentsRoundedIcon from '@mui/icons-material/PaymentsRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency } from '@lib/format';
import { brand } from '@lib/theme';
import { CategoryIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import { useCategories } from './hooks';
import type { ServiceCategory, ServiceSubcategory } from './api';

/** A service match produced by the search box, with its parent category. */
interface ServiceMatch {
  category: ServiceCategory;
  subcategory: ServiceSubcategory;
}

/** Case-insensitive substring match over a service's name and description. */
function matches(query: string, ...fields: (string | undefined)[]): boolean {
  const needle = query.trim().toLowerCase();
  return fields.some((field) => field?.toLowerCase().includes(needle));
}

/**
 * Home screen (Requirement 28.7): the customer's storefront — search across
 * every bookable service, a one-tap emergency path, the service categories as
 * a tile grid, and the shortest visits surfaced as a secondary rail.
 *
 * Search and the rails filter the already-cached catalog on the client rather
 * than issuing a request per keystroke; the catalog is small, changes rarely,
 * and is cached for 5 minutes (see useCategories).
 */
export function HomeScreen() {
  const navigate = useNavigate();
  const { data: categories, isLoading, isError, error, refetch } = useCategories();
  const [query, setQuery] = useState('');
  const [emergencyOnly, setEmergencyOnly] = useState(false);

  const allServices = useMemo<ServiceMatch[]>(
    () =>
      (categories ?? []).flatMap((category) =>
        (category.subcategories ?? []).map((subcategory) => ({ category, subcategory })),
      ),
    [categories],
  );

  const searchResults = useMemo<ServiceMatch[]>(() => {
    if (!query.trim()) return [];
    return allServices.filter(
      ({ category, subcategory }) =>
        matches(query, subcategory.name, subcategory.description) || matches(query, category.name),
    );
  }, [allServices, query]);

  const emergencyServices = useMemo(
    () => allServices.filter((match) => match.subcategory.emergencyAvailable),
    [allServices],
  );

  /**
   * Stands in for a "most booked" rail: booking volume is not exposed by the
   * catalog, so the shortest visits are surfaced rather than inventing
   * popularity the API cannot back.
   */
  const quickestServices = useMemo(
    () =>
      [...allServices]
        .sort((a, b) => a.subcategory.estimatedDurationMin - b.subcategory.estimatedDurationMin)
        .slice(0, 4),
    [allServices],
  );

  const isSearching = query.trim().length > 0;
  // Search always wins over the emergency filter: typing is the more recent intent.
  const listing = isSearching ? searchResults : emergencyOnly ? emergencyServices : null;

  return (
    <AppShell width="full">
      <Stack spacing={2.5}>
        <Box sx={{ textAlign: { xs: 'left', md: 'center' }, pt: { md: 2 } }}>
          <Typography
            component="h1"
            sx={{
              fontSize: { xs: '1.375rem', md: '2.25rem' },
              fontWeight: 800,
              letterSpacing: '-0.03em',
              lineHeight: 1.15,
            }}
          >
            Home services at your doorstep
          </Typography>
          <Typography
            color="text.secondary"
            sx={{ mt: 0.75, fontSize: { xs: '0.8125rem', md: '1rem' } }}
          >
            Background-checked professionals, upfront pricing.
          </Typography>
        </Box>

        <TextField
          fullWidth
          sx={{ maxWidth: { md: 560 }, mx: { md: 'auto' }, width: '100%' }}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search plumber, electrician, AC…"
          inputProps={{ 'aria-label': 'Search services' }}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchRoundedIcon sx={{ color: 'text.secondary' }} />
              </InputAdornment>
            ),
          }}
        />

        {isSearching ? null : <TrustBar />}

        {emergencyServices.length > 0 && !isSearching ? (
          <EmergencyBanner active={emergencyOnly} onToggle={() => setEmergencyOnly((on) => !on)} />
        ) : null}

        <QueryStateView
          isLoading={isLoading}
          isError={isError}
          error={error}
          onRetry={() => void refetch()}
          isEmpty={!categories || categories.length === 0}
          emptyMessage="No services are available right now. Please check back soon."
        >
          {listing ? (
            <ServiceList
              results={listing}
              heading={
                isSearching
                  ? `${listing.length} service${listing.length === 1 ? '' : 's'} found`
                  : 'Available 24×7'
              }
              onSelect={(match) => navigate(`/book/${match.subcategory.id}`)}
            />
          ) : (
            <Stack spacing={3}>
              <Section title="What are you looking for?">
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: {
                      xs: 'repeat(auto-fill, minmax(104px, 1fr))',
                      md: 'repeat(auto-fill, minmax(150px, 1fr))',
                    },
                    gap: 1.5,
                  }}
                >
                  {categories?.map((category) => (
                    <CategoryTile
                      key={category.id}
                      category={category}
                      onSelect={() => navigate(`/categories/${category.id}`)}
                    />
                  ))}
                </Box>
              </Section>

              {quickestServices.length > 0 ? (
                <Section title="Quickest to book" caption="Shortest visits, same verified pros">
                  <ServiceList
                    results={quickestServices}
                    onSelect={(match) => navigate(`/book/${match.subcategory.id}`)}
                  />
                </Section>
              ) : null}
            </Stack>
          )}
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

/** A titled block of the storefront. */
function Section({
  title,
  caption,
  children,
}: {
  title: string;
  caption?: string;
  children: React.ReactNode;
}) {
  return (
    <Box>
      <Stack sx={{ mb: 1.5 }}>
        <Typography variant="h6" component="h2">
          {title}
        </Typography>
        {caption ? (
          <Typography variant="caption" color="text.secondary">
            {caption}
          </Typography>
        ) : null}
      </Stack>
      {children}
    </Box>
  );
}

/**
 * The three promises in the brand line, as a proof-point strip.
 *
 * Deliberately qualitative: a customer count or an aggregate service rating
 * would have to come from the platform's own metrics, and inventing those
 * numbers here would put unverifiable claims in front of customers.
 */
function TrustBar() {
  const points = [
    { icon: <VerifiedRoundedIcon sx={{ fontSize: 18 }} />, label: 'Verified pros' },
    { icon: <ScheduleRoundedIcon sx={{ fontSize: 18 }} />, label: 'On-time visits' },
    { icon: <PaymentsRoundedIcon sx={{ fontSize: 18 }} />, label: 'Upfront pricing' },
  ];

  return (
    <Stack
      direction="row"
      sx={{
        py: { xs: 1.25, md: 2 },
        px: 1,
        borderRadius: 3,
        bgcolor: '#FFFFFF',
        border: `1px solid ${brand.line}`,
        maxWidth: { md: 720 },
        mx: { md: 'auto' },
        width: '100%',
      }}
    >
      {points.map((point) => (
        <Stack
          key={point.label}
          spacing={0.5}
          alignItems="center"
          sx={{ flex: 1, color: 'text.secondary' }}
        >
          <Box sx={{ color: 'success.main', display: 'flex' }}>{point.icon}</Box>
          <Typography variant="caption" fontWeight={600} textAlign="center">
            {point.label}
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}

/** The emergency call-to-action: filters the listing to 24×7 services (Requirement 3.7). */
function EmergencyBanner({ active, onToggle }: { active: boolean; onToggle: () => void }) {
  return (
    <Card
      sx={{
        borderColor: 'transparent',
        background: 'linear-gradient(135deg, #EF4444 0%, #DC2626 100%)',
        color: 'common.white',
      }}
    >
      <CardActionArea
        onClick={onToggle}
        sx={{ p: 2 }}
        aria-pressed={active}
        aria-label={active ? 'Show all services' : 'Show emergency services'}
      >
        <Stack direction="row" spacing={1.5} alignItems="center">
          <Box
            sx={{
              width: 40,
              height: 40,
              borderRadius: 2,
              display: 'grid',
              placeItems: 'center',
              bgcolor: 'rgba(255, 255, 255, 0.2)',
            }}
          >
            <BoltRoundedIcon />
          </Box>
          <Box sx={{ flexGrow: 1 }}>
            <Typography variant="subtitle1" fontWeight={700}>
              Emergency service
            </Typography>
            <Typography variant="body2" sx={{ opacity: 0.9 }}>
              {active
                ? 'Showing 24×7 services — tap to see everything.'
                : 'Get a verified professional on the way, fast.'}
            </Typography>
          </Box>
          <ChevronRightRoundedIcon
            sx={{ transform: active ? 'rotate(90deg)' : 'none', transition: 'transform .2s' }}
          />
        </Stack>
      </CardActionArea>
    </Card>
  );
}

/** One category tile in the storefront grid: colour wash, icon, name. */
function CategoryTile({ category, onSelect }: { category: ServiceCategory; onSelect: () => void }) {
  const art = categoryArt(category.icon, category.name);
  const serviceCount = category.subcategories?.length ?? 0;

  return (
    <CardActionArea
      onClick={onSelect}
      sx={{ borderRadius: 3, p: 0.5 }}
      aria-label={`Browse ${category.name}`}
    >
      <Stack spacing={0.75} alignItems="center">
        <Box
          sx={{
            width: '100%',
            aspectRatio: '1 / 1',
            borderRadius: 3,
            display: 'grid',
            placeItems: 'center',
            bgcolor: art.wash,
            color: art.accent,
          }}
        >
          <CategoryIcon
            iconKey={category.icon}
            categoryName={category.name}
            sx={{ fontSize: 34 }}
            aria-hidden
          />
        </Box>
        <Box sx={{ textAlign: 'center' }}>
          <Typography variant="caption" fontWeight={700} lineHeight={1.25} display="block">
            {category.name}
          </Typography>
          {serviceCount > 0 ? (
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.6875rem' }}>
              {serviceCount} service{serviceCount === 1 ? '' : 's'}
            </Typography>
          ) : null}
        </Box>
      </Stack>
    </CardActionArea>
  );
}

/** Flat list of services — used for search results, the 24×7 filter and rails. */
function ServiceList({
  results,
  heading,
  onSelect,
}: {
  results: ServiceMatch[];
  heading?: string;
  onSelect: (match: ServiceMatch) => void;
}) {
  if (results.length === 0) {
    return (
      <Box sx={{ py: 6, textAlign: 'center' }}>
        <Typography variant="subtitle1">No services match that search</Typography>
        <Typography variant="body2" color="text.secondary">
          Try a broader term, like “plumbing” or “AC”.
        </Typography>
      </Box>
    );
  }

  return (
    <Box>
      {heading ? (
        <Typography variant="overline" color="text.secondary" display="block" sx={{ mb: 1 }}>
          {heading}
        </Typography>
      ) : null}
      <Box
        sx={{
          display: 'grid',
          // A single column of full-width rows looks broken on a wide screen.
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(3, 1fr)' },
          gap: 1.5,
        }}
      >
        {results.map((match) => {
          const art = categoryArt(match.category.icon, match.category.name);
          return (
            <Card key={match.subcategory.id}>
              <CardActionArea
                onClick={() => onSelect(match)}
                sx={{ p: 1.75 }}
                aria-label={`Book ${match.subcategory.name}`}
              >
                <Stack direction="row" spacing={1.5} alignItems="center">
                  <Box
                    sx={{
                      width: 52,
                      height: 52,
                      borderRadius: 2.5,
                      display: 'grid',
                      placeItems: 'center',
                      bgcolor: art.wash,
                      color: art.accent,
                      flexShrink: 0,
                    }}
                  >
                    <CategoryIcon
                      iconKey={match.category.icon}
                      categoryName={match.category.name}
                      sx={{ fontSize: 26 }}
                      aria-hidden
                    />
                  </Box>

                  <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                    <Stack direction="row" spacing={0.75} alignItems="center">
                      <Typography variant="subtitle1" fontWeight={600} noWrap>
                        {match.subcategory.name}
                      </Typography>
                      {match.subcategory.emergencyAvailable ? (
                        <Chip
                          size="small"
                          color="error"
                          icon={<BoltRoundedIcon sx={{ fontSize: 13 }} />}
                          label="24×7"
                        />
                      ) : null}
                    </Stack>
                    <Typography variant="caption" color="text.secondary" display="block">
                      {match.category.name} · {match.subcategory.estimatedDurationMin} min
                    </Typography>
                    <Typography variant="body2" fontWeight={700} sx={{ mt: 0.25 }}>
                      {formatCurrency(match.subcategory.basePrice)}
                      <Box component="span" sx={{ color: 'text.secondary', fontWeight: 400 }}>
                        {' '}
                        onwards
                      </Box>
                    </Typography>
                  </Box>

                  <ChevronRightRoundedIcon sx={{ color: 'text.secondary' }} />
                </Stack>
              </CardActionArea>
            </Card>
          );
        })}
      </Box>
    </Box>
  );
}
