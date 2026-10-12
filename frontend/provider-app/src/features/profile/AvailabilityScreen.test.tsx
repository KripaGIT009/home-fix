import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import type * as ApiModule from './api';
import { saveAvailability, type ProviderProfile } from './api';
import { WeeklyAvailabilityForm } from './AvailabilityScreen';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return { ...actual, saveAvailability: vi.fn() };
});

const PROFILE: ProviderProfile = {
  id: 'p1',
  displayName: 'Asha',
  yearsExperience: 4,
  serviceRadiusKm: 10,
  baseLatitude: 12.97,
  baseLongitude: 77.59,
  aggregateRating: 0,
  emergencyAvailable: false,
  underReview: false,
  skillTags: ['plumbing'],
  categories: [],
  availability: [],
};

function setup(profile: ProviderProfile = PROFILE) {
  const onSaved = vi.fn();
  renderWithProviders(<WeeklyAvailabilityForm profile={profile} onSaved={onSaved} />);
  return { onSaved, user: userEvent.setup() };
}

describe('WeeklyAvailabilityForm', () => {
  it('saves weekly hours added for a day', async () => {
    vi.mocked(saveAvailability).mockResolvedValue(PROFILE);
    const { user, onSaved } = setup();

    expect(screen.getByRole('radio', { name: /Any time/ })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: 'Only during the hours I set' }));
    await user.click(screen.getByRole('button', { name: 'Add hours on Monday' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Monday to' }), '17');
    await user.click(screen.getByRole('button', { name: 'Save hours' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(vi.mocked(saveAvailability).mock.calls[0]?.[0]).toEqual([
      { dayOfWeek: 'MONDAY', startHour: 9, endHour: 17 },
    ]);
  });

  it('flags overlapping ranges on the same day', async () => {
    const { user } = setup({
      ...PROFILE,
      availability: [
        { dayOfWeek: 'MONDAY', startHour: 9, endHour: 13 },
        { dayOfWeek: 'MONDAY', startHour: 13, endHour: 18 },
      ],
    });

    const [, secondFrom] = screen.getAllByRole('combobox', { name: 'Monday from' });
    await user.selectOptions(secondFrom!, '11');
    await user.click(screen.getByRole('button', { name: 'Save hours' }));

    expect(
      await screen.findByText('This overlaps another time range on the same day'),
    ).toBeInTheDocument();
    expect(saveAvailability).not.toHaveBeenCalled();
  });

  it('clears the schedule when switched back to any time', async () => {
    vi.mocked(saveAvailability).mockResolvedValue(PROFILE);
    const { user, onSaved } = setup({
      ...PROFILE,
      availability: [{ dayOfWeek: 'FRIDAY', startHour: 8, endHour: 20 }],
    });

    await user.click(screen.getByRole('radio', { name: /Any time/ }));
    await user.click(screen.getByRole('button', { name: 'Save hours' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(vi.mocked(saveAvailability).mock.calls[0]?.[0]).toEqual([]);
  });
});
