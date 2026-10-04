import { describe, expect, it } from 'vitest';
import type { ProviderProfile } from './api';
import { assessProfile } from './completeness';

const COMPLETE: ProviderProfile = {
  id: 'p1',
  displayName: 'Ravi',
  yearsExperience: 3,
  serviceRadiusKm: 10,
  baseLatitude: 12.97,
  baseLongitude: 77.59,
  aggregateRating: 0,
  emergencyAvailable: false,
  underReview: false,
  skillTags: ['plumbing'],
  categories: [{ categoryId: 'c1', subcategoryIds: ['s1'] }],
  availability: [],
};

describe('assessProfile', () => {
  it('needs every step when no profile has been saved', () => {
    expect(assessProfile(null)).toEqual({
      complete: false,
      missing: ['services', 'serviceArea'],
      underReview: false,
    });
  });

  it('is complete with skills and a base location, without a weekly schedule', () => {
    expect(assessProfile(COMPLETE)).toEqual({ complete: true, missing: [], underReview: false });
  });

  it('needs a base location', () => {
    expect(assessProfile({ ...COMPLETE, baseLatitude: null, baseLongitude: null }).missing).toEqual(
      ['serviceArea'],
    );
  });

  it('needs at least one skill tag', () => {
    expect(assessProfile({ ...COMPLETE, skillTags: [] }).missing).toEqual(['services']);
  });

  it('reports an admin review separately from completeness', () => {
    expect(assessProfile({ ...COMPLETE, underReview: true })).toEqual({
      complete: true,
      missing: [],
      underReview: true,
    });
  });
});
