import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import { readDevicePosition } from '@features/jobs/deviceLocation';
import type * as ApiModule from './api';
import { saveProfile, saveServiceRadius, type ProviderProfile } from './api';
import { ServiceAreaForm } from './ServiceAreaScreen';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, saveProfile: vi.fn(), saveServiceRadius: vi.fn() };
});

vi.mock('@features/jobs/deviceLocation', () => ({ readDevicePosition: vi.fn() }));

const PROFILE: ProviderProfile = {
  id: 'p1',
  displayName: 'Asha',
  yearsExperience: 4,
  serviceRadiusKm: 10,
  baseLatitude: null,
  baseLongitude: null,
  aggregateRating: 0,
  emergencyAvailable: false,
  underReview: false,
  skillTags: ['plumbing'],
  categories: [{ categoryId: 'plumbing', subcategoryIds: ['tap'] }],
  availability: [],
};

function setup(profile: ProviderProfile = PROFILE) {
  const onSaved = vi.fn();
  renderWithProviders(<ServiceAreaForm profile={profile} onSaved={onSaved} />);
  return { onSaved, user: userEvent.setup() };
}

describe('ServiceAreaForm', () => {
  it('fills the base location from the device and saves it with the profile', async () => {
    vi.mocked(readDevicePosition).mockResolvedValue({
      status: 'ok',
      latitude: 12.97159912,
      longitude: 77.59460001,
      accuracyMeters: 18,
    });
    vi.mocked(saveProfile).mockResolvedValue({
      ...PROFILE,
      baseLatitude: 12.971599,
      baseLongitude: 77.5946,
    });
    const { user, onSaved } = setup();

    expect(screen.getByText(/You have no base location yet/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Use my current location' }));

    expect(await screen.findByText(/accurate to about 18 m/)).toBeInTheDocument();
    expect(screen.getByLabelText('Latitude')).toHaveValue('12.971599');
    expect(screen.getByLabelText('Longitude')).toHaveValue('77.594600');

    await user.click(screen.getByRole('button', { name: 'Save service area' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(vi.mocked(saveProfile).mock.calls[0]?.[0]).toEqual({
      displayName: 'Asha',
      categories: PROFILE.categories,
      skillTags: ['plumbing'],
      yearsExperience: 4,
      serviceRadiusKm: 10,
      baseLatitude: 12.971599,
      baseLongitude: 77.5946,
    });
  });

  it('falls back to typed coordinates when location permission is refused', async () => {
    vi.mocked(readDevicePosition).mockResolvedValue({ status: 'denied' });
    vi.mocked(saveProfile).mockResolvedValue(PROFILE);
    const { user, onSaved } = setup();

    await user.click(screen.getByRole('button', { name: 'Use my current location' }));
    expect(await screen.findByText(/Location permission is turned off/)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Latitude'), '13.0827');
    await user.type(screen.getByLabelText('Longitude'), '80.2707');
    await user.click(screen.getByRole('button', { name: 'Save service area' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(vi.mocked(saveProfile).mock.calls[0]?.[0]).toMatchObject({
      baseLatitude: 13.0827,
      baseLongitude: 80.2707,
    });
  });

  it('rejects coordinates out of range without saving', async () => {
    const { user } = setup();

    await user.type(screen.getByLabelText('Latitude'), '95');
    await user.type(screen.getByLabelText('Longitude'), 'east');
    await user.click(screen.getByRole('button', { name: 'Save service area' }));

    expect(await screen.findByText(/Enter a latitude between -90 and 90/)).toBeInTheDocument();
    expect(screen.getByText(/Enter a longitude between -180 and 180/)).toBeInTheDocument();
    expect(saveProfile).not.toHaveBeenCalled();
  });

  it('saves only the radius when the location is unchanged', async () => {
    const located = { ...PROFILE, baseLatitude: 12.9716, baseLongitude: 77.5946 };
    vi.mocked(saveServiceRadius).mockResolvedValue(located);
    const { user, onSaved } = setup(located);

    expect(screen.queryByText(/You have no base location yet/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save service area' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(vi.mocked(saveServiceRadius).mock.calls[0]?.[0]).toBe(10);
    expect(saveProfile).not.toHaveBeenCalled();
  });
});
