import { useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Button,
  Card,
  CardActionArea,
  Container,
  Divider,
  IconButton,
  InputBase,
  Stack,
  Typography,
} from '@mui/material';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import NearMeRoundedIcon from '@mui/icons-material/NearMeRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import TouchAppRoundedIcon from '@mui/icons-material/TouchAppRounded';
import SearchOffRoundedIcon from '@mui/icons-material/SearchOffRounded';
import { AppShell, DEFAULT_SERVICE_AREA } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { EmptyState, IconTile } from '@components/StateViews';
import { formatPrice } from '@lib/format';
import { brand, heroBackground, layout, radius, shadows, visuallyHidden } from '@lib/theme';
import { CategoryIcon } from './categoryIcon';
import { categoryArt } from './categoryArt';
import { useCategories } from './hooks';
import { ServiceCard } from './ServiceCard';
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
 * Home screen (Requirement 28.7): the customer's storefront — a hero with
 * search across every bookable service, the categories, the quickest services
 * to book, a calm emergency entry point, how booking works and why to trust it.
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
  const resultsRef = useRef<HTMLDivElement | null>(null);

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
        .slice(0, 6),
    [allServices],
  );

  const isSearching = query.trim().length > 0;
  // Search always wins over the emergency filter: typing is the more recent intent.
  const listing = isSearching ? searchResults : emergencyOnly ? emergencyServices : null;
  const book = (match: ServiceMatch) => navigate(`/book/${match.subcategory.id}`);

  const scrollToResults = () =>
    resultsRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });

  return (
    <AppShell width="bleed" footer>
      <Hero
        query={query}
        onQueryChange={setQuery}
        onSubmit={scrollToResults}
        suggestions={quickestServices.slice(0, 3)}
        onSuggestion={book}
        featured={quickestServices[0]}
      />

      <Container
        maxWidth={false}
        sx={{ maxWidth: layout.maxWidth, px: layout.gutter, pt: { xs: 3, md: 6 } }}
      >
        <Box ref={resultsRef} sx={{ scrollMarginTop: 88 }}>
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
            {listing ? (
              <Section
                title={
                  isSearching
                    ? `${listing.length} service${listing.length === 1 ? '' : 's'} for “${query.trim()}”`
                    : 'Available 24×7'
                }
                caption={
                  isSearching ? undefined : 'Emergency-ready services, with a pro dispatched now.'
                }
                action={
                  <Button
                    onClick={() => {
                      setQuery('');
                      setEmergencyOnly(false);
                    }}
                    startIcon={<CloseRoundedIcon />}
                  >
                    {isSearching ? 'Clear search' : 'Show all services'}
                  </Button>
                }
              >
                {listing.length === 0 ? (
                  <EmptyState
                    compact
                    icon={<SearchOffRoundedIcon />}
                    title="No services match that search"
                    description="Try a broader term, like “plumbing” or “AC”."
                  />
                ) : (
                  <ServiceGrid results={listing} onSelect={book} />
                )}
              </Section>
            ) : (
              <Stack spacing={{ xs: 5, md: 8 }}>
                <Section
                  title="Browse by category"
                  caption="Every pro is verified before their first job."
                >
                  <Box
                    sx={{
                      display: 'grid',
                      gridTemplateColumns: {
                        xs: 'repeat(2, minmax(0, 1fr))',
                        sm: 'repeat(3, minmax(0, 1fr))',
                        md: 'repeat(auto-fill, minmax(220px, 1fr))',
                      },
                      gap: { xs: 1.5, md: 2.5 },
                    }}
                  >
                    {categories?.map((category) => (
                      <CategoryCard
                        key={category.id}
                        category={category}
                        onSelect={() => navigate(`/categories/${category.id}`)}
                      />
                    ))}
                  </Box>
                </Section>

                {emergencyServices.length > 0 ? (
                  <EmergencyCard
                    count={emergencyServices.length}
                    onOpen={() => {
                      setEmergencyOnly(true);
                      scrollToResults();
                    }}
                  />
                ) : null}

                {quickestServices.length > 0 ? (
                  <Section title="Quickest to book" caption="Short visits, same verified pros.">
                    <ServiceGrid results={quickestServices} onSelect={book} />
                  </Section>
                ) : null}

                <HowItWorks />
                <TrustSection />
              </Stack>
            )}
          </QueryStateView>
        </Box>
      </Container>
    </AppShell>
  );
}

/* -------------------------------------------------------------------------- */
/* Hero                                                                        */
/* -------------------------------------------------------------------------- */

function Hero({
  query,
  onQueryChange,
  onSubmit,
  suggestions,
  onSuggestion,
  featured,
}: {
  query: string;
  onQueryChange: (value: string) => void;
  onSubmit: () => void;
  suggestions: ServiceMatch[];
  onSuggestion: (match: ServiceMatch) => void;
  featured: ServiceMatch | undefined;
}) {
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };

  return (
    <Box
      component="section"
      aria-labelledby="home-hero-title"
      sx={{
        background: heroBackground,
        borderBottom: `1px solid ${brand.line}`,
        overflow: 'hidden',
      }}
    >
      <Container
        maxWidth={false}
        sx={{
          maxWidth: layout.maxWidth,
          px: layout.gutter,
          py: { xs: 3.5, md: 9 },
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1.15fr 0.85fr' },
          gap: { md: 6 },
          alignItems: 'center',
        }}
      >
        <Box>
          <Stack
            direction="row"
            spacing={0.75}
            alignItems="center"
            sx={{
              display: 'inline-flex',
              px: 1.25,
              py: 0.5,
              mb: { xs: 1.5, md: 2.5 },
              borderRadius: `${radius.pill}px`,
              bgcolor: 'rgba(255,255,255,0.8)',
              border: `1px solid ${brand.accentLine}`,
              color: brand.accentDark,
            }}
          >
            <VerifiedUserRoundedIcon sx={{ fontSize: 16 }} aria-hidden />
            <Typography variant="caption" fontWeight={700}>
              Background-checked pros in {DEFAULT_SERVICE_AREA}
            </Typography>
          </Stack>

          <Typography
            id="home-hero-title"
            variant="h1"
            sx={{ fontSize: { xs: '1.875rem', sm: '2.5rem', md: '3.25rem' }, maxWidth: 620 }}
          >
            Expert help for every corner of your{' '}
            <Box component="span" sx={{ color: 'primary.main' }}>
              home
            </Box>
            .
          </Typography>
          <Typography
            color="text.secondary"
            sx={{
              mt: { xs: 1.25, md: 2 },
              fontSize: { xs: '1rem', md: '1.1875rem' },
              maxWidth: 540,
            }}
          >
            Book plumbers, electricians, cleaners and more. See the full price before you confirm,
            then track your pro to the door.
          </Typography>

          <Box
            component="form"
            role="search"
            onSubmit={handleSubmit}
            sx={{
              mt: { xs: 2.5, md: 4 },
              display: 'flex',
              alignItems: 'center',
              maxWidth: 620,
              p: 0.75,
              pl: { xs: 1.75, md: 1 },
              bgcolor: 'background.paper',
              borderRadius: `${radius.lg}px`,
              border: `1px solid ${brand.lineStrong}`,
              boxShadow: shadows.raised,
              '&:focus-within': {
                borderColor: 'primary.main',
                boxShadow: `${shadows.raised}, ${shadows.focus}`,
              },
            }}
          >
            <Stack
              direction="row"
              spacing={0.75}
              alignItems="center"
              sx={{ display: { xs: 'none', md: 'flex' }, pl: 1.25, pr: 1.5, flexShrink: 0 }}
            >
              <PlaceRoundedIcon sx={{ color: 'primary.main', fontSize: 20 }} aria-hidden />
              <Typography variant="body2" fontWeight={700} noWrap>
                {DEFAULT_SERVICE_AREA}
              </Typography>
            </Stack>
            <Divider
              orientation="vertical"
              flexItem
              sx={{ display: { xs: 'none', md: 'block' }, my: 1 }}
            />
            <SearchRoundedIcon
              sx={{ color: 'text.secondary', ml: { md: 1.75 }, mr: 1.25 }}
              aria-hidden
            />
            <InputBase
              value={query}
              onChange={(event) => onQueryChange(event.target.value)}
              placeholder="Search for a service, e.g. tap repair"
              inputProps={{ 'aria-label': 'Search services', enterKeyHint: 'search' }}
              sx={{
                flexGrow: 1,
                fontSize: { xs: '1rem', md: '1.0625rem' },
                py: { xs: 0.75, md: 1.25 },
              }}
            />
            {query ? (
              <IconButton aria-label="Clear search" onClick={() => onQueryChange('')} size="small">
                <CloseRoundedIcon fontSize="small" />
              </IconButton>
            ) : null}
            <Button
              type="submit"
              variant="contained"
              size="large"
              sx={{ display: { xs: 'none', sm: 'inline-flex' }, ml: 1, px: 3.5 }}
            >
              Search
            </Button>
          </Box>

          {suggestions.length > 0 ? (
            <Stack
              direction="row"
              spacing={1}
              alignItems="center"
              flexWrap="wrap"
              useFlexGap
              sx={{ mt: 2 }}
            >
              <Typography variant="body2" color="text.secondary" fontWeight={600}>
                Quick picks:
              </Typography>
              {suggestions.map((match) => (
                <Button
                  key={match.subcategory.id}
                  size="small"
                  variant="outlined"
                  onClick={() => onSuggestion(match)}
                  sx={{ borderRadius: `${radius.pill}px`, bgcolor: 'rgba(255,255,255,0.7)' }}
                >
                  {match.subcategory.name}
                </Button>
              ))}
            </Stack>
          ) : null}
        </Box>

        <HeroIllustration featured={featured} />
      </Container>
    </Box>
  );
}

/**
 * A decorative composition built from the product's own UI: a line-art house,
 * a "pro on the way" card and the real cheapest-quickest service. No stock
 * imagery, nothing claimed that the catalog cannot back.
 */
function HeroIllustration({ featured }: { featured: ServiceMatch | undefined }) {
  const art = featured ? categoryArt(featured.category.icon, featured.category.name) : null;
  return (
    <Box
      aria-hidden
      sx={{
        display: { xs: 'none', md: 'block' },
        position: 'relative',
        height: 380,
      }}
    >
      <Box
        sx={{
          position: 'absolute',
          inset: '6% 4% 2% 10%',
          borderRadius: '50%',
          background: `radial-gradient(circle at 50% 45%, ${brand.accentSoft} 0%, rgba(238,242,253,0) 70%)`,
        }}
      />
      <Box
        component="svg"
        viewBox="0 0 320 280"
        sx={{ position: 'absolute', left: '6%', top: '20%', width: '64%', height: 'auto' }}
      >
        <path
          d="M40 132 160 36l120 96"
          fill="none"
          stroke={brand.accent}
          strokeWidth="10"
          strokeLinecap="round"
          strokeLinejoin="round"
          opacity="0.9"
        />
        <path
          d="M70 118v124a14 14 0 0 0 14 14h152a14 14 0 0 0 14-14V118"
          fill="#FFFFFF"
          stroke={brand.accentLine}
          strokeWidth="6"
          strokeLinejoin="round"
        />
        <rect x="134" y="178" width="52" height="78" rx="8" fill={brand.accentSoft} />
        <circle cx="175" cy="219" r="3.5" fill={brand.accent} />
        <rect
          x="92"
          y="140"
          width="40"
          height="34"
          rx="6"
          fill={brand.warmSoft}
          stroke={brand.warm}
          strokeWidth="3"
        />
        <rect
          x="188"
          y="140"
          width="40"
          height="34"
          rx="6"
          fill={brand.warmSoft}
          stroke={brand.warm}
          strokeWidth="3"
        />
        <path d="M232 70v34" stroke={brand.lineStrong} strokeWidth="10" strokeLinecap="round" />
      </Box>

      <Box
        sx={{
          position: 'absolute',
          left: 0,
          bottom: 28,
          width: 270,
          p: 2,
          borderRadius: `${radius.lg}px`,
          bgcolor: 'background.paper',
          boxShadow: shadows.overlay,
          border: `1px solid ${brand.line}`,
        }}
      >
        <Stack direction="row" spacing={1.5} alignItems="center">
          <IconTile size={40}>
            <NearMeRoundedIcon />
          </IconTile>
          <Box sx={{ flexGrow: 1 }}>
            <Typography variant="subtitle2" fontWeight={700}>
              Your pro is on the way
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Live location and ETA
            </Typography>
          </Box>
        </Stack>
        <Box
          sx={{ mt: 1.5, height: 6, borderRadius: 3, bgcolor: brand.slateSoft, overflow: 'hidden' }}
        >
          <Box sx={{ width: '64%', height: '100%', borderRadius: 3, bgcolor: 'primary.main' }} />
        </Box>
      </Box>

      {featured && art ? (
        <Box
          sx={{
            position: 'absolute',
            right: 0,
            top: 0,
            width: 240,
            p: 2,
            borderRadius: `${radius.lg}px`,
            bgcolor: 'background.paper',
            boxShadow: shadows.overlay,
            border: `1px solid ${brand.line}`,
          }}
        >
          <Stack direction="row" spacing={1.25} alignItems="center">
            <IconTile size={36} bg={art.wash} color={art.accent}>
              <CategoryIcon
                iconKey={featured.category.icon}
                categoryName={featured.category.name}
              />
            </IconTile>
            <Typography variant="subtitle2" fontWeight={700} noWrap>
              {featured.subcategory.name}
            </Typography>
          </Stack>
          <Stack
            direction="row"
            justifyContent="space-between"
            alignItems="baseline"
            sx={{ mt: 1.5 }}
          >
            <Typography variant="caption" color="text.secondary">
              Upfront price from
            </Typography>
            <Typography variant="h5" component="span" fontWeight={800}>
              {formatPrice(featured.subcategory.basePrice)}
            </Typography>
          </Stack>
        </Box>
      ) : null}

      <Stack
        direction="row"
        spacing={0.75}
        alignItems="center"
        sx={{
          position: 'absolute',
          right: 36,
          bottom: 0,
          px: 1.5,
          py: 0.75,
          borderRadius: `${radius.pill}px`,
          bgcolor: brand.greenSoft,
          color: '#05603A',
          border: '1px solid #C7EBD7',
          boxShadow: shadows.card,
        }}
      >
        <VerifiedUserRoundedIcon sx={{ fontSize: 16 }} />
        <Typography variant="caption" fontWeight={700}>
          Background-checked
        </Typography>
      </Stack>
    </Box>
  );
}

/* -------------------------------------------------------------------------- */
/* Sections                                                                    */
/* -------------------------------------------------------------------------- */

/** A titled block of the storefront. */
function Section({
  title,
  caption,
  action,
  children,
}: {
  title: string;
  caption?: string | undefined;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Box component="section">
      <Stack
        direction="row"
        alignItems="flex-end"
        justifyContent="space-between"
        spacing={2}
        sx={{ mb: { xs: 2, md: 3 } }}
      >
        <Box>
          <Typography variant="h3" component="h2">
            {title}
          </Typography>
          {caption ? (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {caption}
            </Typography>
          ) : null}
        </Box>
        {action}
      </Stack>
      {children}
    </Box>
  );
}

/** One category in the storefront grid. */
function CategoryCard({ category, onSelect }: { category: ServiceCategory; onSelect: () => void }) {
  const art = categoryArt(category.icon, category.name);
  const serviceCount = category.subcategories?.length ?? 0;

  return (
    <Card
      sx={{
        height: '100%',
        transition: 'box-shadow .2s, border-color .2s, transform .2s',
        '&:hover': {
          boxShadow: shadows.raised,
          borderColor: brand.lineStrong,
          transform: 'translateY(-2px)',
        },
      }}
    >
      <CardActionArea
        onClick={onSelect}
        aria-label={`Browse ${category.name}`}
        sx={{
          height: '100%',
          p: { xs: 2, md: 2.5 },
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'stretch',
        }}
      >
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
          <IconTile size={52} bg={art.wash} color={art.accent}>
            <CategoryIcon iconKey={category.icon} categoryName={category.name} />
          </IconTile>
          <ArrowForwardRoundedIcon
            sx={{ color: 'text.disabled', fontSize: 20, display: { xs: 'none', sm: 'block' } }}
            aria-hidden
          />
        </Stack>
        <Typography variant="h6" component="h3" sx={{ mt: { xs: 1.5, md: 2.5 }, lineHeight: 1.3 }}>
          {category.name}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {serviceCount > 0
            ? `${serviceCount} service${serviceCount === 1 ? '' : 's'}`
            : 'Coming soon'}
        </Typography>
      </CardActionArea>
    </Card>
  );
}

/** Responsive grid of bookable service cards. */
function ServiceGrid({
  results,
  onSelect,
}: {
  results: ServiceMatch[];
  onSelect: (match: ServiceMatch) => void;
}) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: {
          xs: '1fr',
          sm: 'repeat(2, minmax(0, 1fr))',
          md: 'repeat(3, minmax(0, 1fr))',
        },
        gap: { xs: 1.5, md: 2.5 },
      }}
    >
      {results.map((match) => (
        <ServiceCard
          key={match.subcategory.id}
          category={match.category}
          subcategory={match.subcategory}
          onSelect={() => onSelect(match)}
        />
      ))}
    </Box>
  );
}

/** The emergency entry point: a compact card, red only in its accent. */
function EmergencyCard({ count, onOpen }: { count: number; onOpen: () => void }) {
  return (
    <Card
      component="section"
      aria-labelledby="emergency-title"
      sx={{
        position: 'relative',
        overflow: 'hidden',
        borderColor: '#F7D4D0',
        '&::before': {
          content: '""',
          position: 'absolute',
          left: 0,
          top: 0,
          bottom: 0,
          width: 4,
          bgcolor: 'error.main',
        },
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={{ xs: 2, sm: 2.5 }}
        alignItems={{ xs: 'flex-start', sm: 'center' }}
        sx={{ p: { xs: 2.25, md: 3 }, pl: { xs: 2.75, md: 3.5 } }}
      >
        <IconTile size={48} bg={brand.redSoft} color={brand.red}>
          <BoltRoundedIcon />
        </IconTile>
        <Box sx={{ flexGrow: 1 }}>
          <Typography id="emergency-title" variant="h5" component="h2">
            Something urgent?
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {count} service{count === 1 ? ' is' : 's are'} available 24×7 — we dispatch the nearest
            verified pro right away.
          </Typography>
        </Box>
        <Button
          variant="contained"
          color="error"
          size="large"
          onClick={onOpen}
          endIcon={<ArrowForwardRoundedIcon />}
          sx={{ flexShrink: 0, width: { xs: '100%', sm: 'auto' } }}
        >
          Get emergency help
        </Button>
      </Stack>
    </Card>
  );
}

const STEPS = [
  {
    icon: <TouchAppRoundedIcon />,
    title: 'Pick a service',
    body: 'Choose what you need and tell us where and when. Add photos if it helps.',
  },
  {
    icon: <ReceiptLongRoundedIcon />,
    title: 'See the price upfront',
    body: 'Get an itemised estimate before you confirm. No surprises later.',
  },
  {
    icon: <NearMeRoundedIcon />,
    title: 'Track your pro',
    body: 'Follow them live to your door, chat in the app, and pay when the job is done.',
  },
] as const;

function HowItWorks() {
  return (
    <Section title="How HomeFix works">
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
          gap: { xs: 1.5, md: 2.5 },
          counterReset: 'step',
        }}
      >
        {STEPS.map((step, index) => (
          <Stack
            key={step.title}
            direction={{ xs: 'row', md: 'column' }}
            spacing={2}
            sx={{
              p: { xs: 2, md: 3 },
              borderRadius: `${radius.lg}px`,
              bgcolor: 'background.paper',
              border: `1px solid ${brand.line}`,
            }}
          >
            <Box sx={{ position: 'relative', flexShrink: 0, alignSelf: 'flex-start' }}>
              <IconTile size={48}>{step.icon}</IconTile>
              <Box
                aria-hidden
                sx={{
                  position: 'absolute',
                  top: -8,
                  right: -8,
                  width: 22,
                  height: 22,
                  borderRadius: '50%',
                  display: 'grid',
                  placeItems: 'center',
                  bgcolor: brand.warm,
                  color: '#3B2400',
                  fontSize: '0.75rem',
                  fontWeight: 800,
                  border: '2px solid #fff',
                }}
              >
                {index + 1}
              </Box>
            </Box>
            <Box>
              <Typography variant="h6" component="h3">
                <Box component="span" sx={visuallyHidden}>
                  Step {index + 1}:{' '}
                </Box>
                {step.title}
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                {step.body}
              </Typography>
            </Box>
          </Stack>
        ))}
      </Box>
    </Section>
  );
}

const PROMISES = [
  {
    icon: <VerifiedUserRoundedIcon />,
    title: 'Verified professionals',
    body: 'Document and background checks before anyone takes their first job.',
  },
  {
    icon: <ReceiptLongRoundedIcon />,
    title: 'Upfront, itemised pricing',
    body: 'You see every charge before you book, and approve any change before it is billed.',
  },
  {
    icon: <ScheduleRoundedIcon />,
    title: 'On time, tracked live',
    body: 'Watch your pro arrive with live location and ETA, and message them in the app.',
  },
] as const;

function TrustSection() {
  return (
    <Box
      component="section"
      aria-labelledby="trust-title"
      sx={{
        p: { xs: 3, md: 6 },
        borderRadius: `${radius.xl}px`,
        color: 'common.white',
        background: `radial-gradient(600px 300px at 100% 0%, rgba(242,165,22,0.18) 0%, transparent 60%), linear-gradient(150deg, ${brand.accentDark} 0%, ${brand.accentDeep} 100%)`,
      }}
    >
      <Typography id="trust-title" variant="h3" component="h2" sx={{ maxWidth: 520 }}>
        Why homes trust HomeFix
      </Typography>
      <Typography sx={{ mt: 1, opacity: 0.8, maxWidth: 520 }} variant="body1">
        The same promise on every booking, whatever the job.
      </Typography>
      <Box
        sx={{
          mt: { xs: 3, md: 5 },
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
          gap: { xs: 2.5, md: 4 },
        }}
      >
        {PROMISES.map((promise) => (
          <Stack key={promise.title} direction={{ xs: 'row', md: 'column' }} spacing={2}>
            <IconTile size={48} bg="rgba(255,255,255,0.12)" color={brand.warm}>
              {promise.icon}
            </IconTile>
            <Box>
              <Typography variant="h6" component="h3">
                {promise.title}
              </Typography>
              <Typography variant="body2" sx={{ opacity: 0.8, mt: 0.5 }}>
                {promise.body}
              </Typography>
            </Box>
          </Stack>
        ))}
      </Box>
    </Box>
  );
}
