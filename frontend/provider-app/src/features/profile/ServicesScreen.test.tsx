import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@api/client';
import { renderWithProviders } from '@/test/render';
import type * as ApiModule from './api';
import { fetchServiceCatalog, saveProfile, type CatalogCategory, type ProviderProfile } from './api';
import { ServicesForm } from './ServicesScreen';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, saveProfile: vi.fn(), fetchServiceCatalog: vi.fn() };
});

const CATALOG: CatalogCategory[] = [
  {
    id: 'plumbing',
    name: 'Plumbing',
    description: null,
    displayOrder: 1,
    subcategories: [
      {
        id: 'tap',
        categoryId: 'plumbing',
        name: 'Tap repair',
        skillTags: ['plumbing', 'tap repair'],
        emergencyAvailable: true,
      },
    ],
  },
  {
    id: 'electrical',
    name: 'Electrical',
    description: null,
    displayOrder: 2,
    subcategories: [
      {
        id: 'fan',
        categoryId: 'electrical',
        name: 'Fan installation',
        skillTags: ['electrical'],
        emergencyAvailable: false,
      },
    ],
  },
];

const SAVED: ProviderProfile = {
  id: 'p1',
  displayName: 'Asha',
  yearsExperience: 4,
  serviceRadiusKm: 10,
  baseLatitude: null,
  baseLongitude: null,
  aggregateRating: 0,
  emergencyAvailable: false,
  underReview: false,
  skillTags: ['plumbing', 'tap repair', 'electrical'],
  categories: [
    { categoryId: 'plumbing', subcategoryIds: ['tap'] },
    { categoryId: 'electrical', subcategoryIds: ['fan'] },
  ],
  availability: [],
};

function setup(profile: ProviderProfile | null = null) {
  vi.mocked(fetchServiceCatalog).mockResolvedValue(CATALOG);
  const onSaved = vi.fn();
  renderWithProviders(<ServicesForm profile={profile} catalog={CATALOG} onSaved={onSaved} />);
  return { onSaved, user: userEvent.setup() };
}

describe('ServicesForm', () => {
  it('lists the catalog services grouped by category', () => {
    setup();
    expect(screen.getByRole('heading', { name: 'Plumbing' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Tap repair' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Fan installation' })).not.toBeChecked();
  });

  it('does not save without a service and years of experience', async () => {
    const { user } = setup();

    await user.click(screen.getByRole('button', { name: 'Save and continue' }));

    expect(await screen.findByText('Pick at least one service you offer')).toBeInTheDocument();
    expect(screen.getByText(/Enter whole years, from 0 to 50/)).toBeInTheDocument();
    expect(saveProfile).not.toHaveBeenCalled();
  });

  it('shows the skills the chosen services bring and saves them', async () => {
    vi.mocked(saveProfile).mockResolvedValue(SAVED);
    const { user, onSaved } = setup();

    await user.type(screen.getByLabelText('Name shown to customers'), 'Asha');
    await user.type(screen.getByLabelText('Years of experience'), '4');
    await user.click(screen.getByRole('checkbox', { name: 'Tap repair' }));
    await user.click(screen.getByRole('checkbox', { name: 'Fan installation' }));

    expect(screen.getByText('tap repair')).toBeInTheDocument();
    expect(screen.getByText('electrical')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save and continue' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(SAVED));
    expect(vi.mocked(saveProfile).mock.calls[0]?.[0]).toEqual({
      displayName: 'Asha',
      categories: [
        { categoryId: 'plumbing', subcategoryIds: ['tap'] },
        { categoryId: 'electrical', subcategoryIds: ['fan'] },
      ],
      skillTags: ['plumbing', 'tap repair', 'electrical'],
      yearsExperience: 4,
      serviceRadiusKm: 10,
    });
  });

  it('starts from the saved profile and keeps its radius', async () => {
    vi.mocked(saveProfile).mockResolvedValue(SAVED);
    const { user } = setup({ ...SAVED, serviceRadiusKm: 30 });

    expect(screen.getByRole('checkbox', { name: 'Tap repair' })).toBeChecked();
    expect(screen.getByLabelText('Years of experience')).toHaveValue('4');

    await user.click(screen.getByRole('checkbox', { name: 'Fan installation' }));
    await user.click(screen.getByRole('button', { name: 'Save services' }));

    await waitFor(() => expect(saveProfile).toHaveBeenCalled());
    expect(vi.mocked(saveProfile).mock.calls[0]?.[0]).toMatchObject({
      categories: [{ categoryId: 'plumbing', subcategoryIds: ['tap'] }],
      skillTags: ['plumbing', 'tap repair'],
      serviceRadiusKm: 30,
    });
  });

  it('explains a service that was switched off while choosing', async () => {
    vi.mocked(saveProfile).mockRejectedValue(
      new ApiError({ status: 400, code: 'SUBCATEGORY_DEACTIVATED', message: 'deactivated' }),
    );
    const { user } = setup(SAVED);

    await user.click(screen.getByRole('button', { name: 'Save services' }));

    expect(
      await screen.findByText(/One of the services you picked is no longer offered/),
    ).toBeInTheDocument();
  });
});
