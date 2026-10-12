import { describe, expect, it } from 'vitest';
import type { CatalogCategory, ProviderProfile } from './api';
import {
  availabilitySchema,
  buildServicesSchema,
  deriveSelection,
  serviceAreaSchema,
  servicesFormDefaults,
  toAvailabilityPayload,
  toProfilePayload,
  toServiceAreaChange,
} from './schemas';

function category(id: string, subs: { id: string; tags: string[] }[]): CatalogCategory {
  return {
    id,
    name: `Category ${id}`,
    description: null,
    displayOrder: 0,
    subcategories: subs.map((sub) => ({
      id: sub.id,
      categoryId: id,
      name: `Service ${sub.id}`,
      skillTags: sub.tags,
      emergencyAvailable: false,
    })),
  };
}

const CATALOG: CatalogCategory[] = [
  category('plumbing', [
    { id: 'tap', tags: ['plumbing', 'Tap Repair'] },
    { id: 'pipe', tags: ['Plumbing', 'pipe fitting'] },
  ]),
  category('electrical', [{ id: 'fan', tags: ['electrical'] }]),
  category('empty', [{ id: 'untagged', tags: [] }]),
];

const PROFILE: ProviderProfile = {
  id: 'p1',
  displayName: 'Ravi Kumar',
  yearsExperience: 6,
  serviceRadiusKm: 15,
  baseLatitude: 12.9716,
  baseLongitude: 77.5946,
  aggregateRating: 4.5,
  emergencyAvailable: false,
  underReview: false,
  skillTags: ['plumbing'],
  categories: [{ categoryId: 'plumbing', subcategoryIds: ['tap'] }],
  availability: [],
};

describe('deriveSelection', () => {
  it('groups chosen services by category and unions their tags case-insensitively', () => {
    expect(deriveSelection(['pipe', 'fan', 'tap'], CATALOG)).toEqual({
      categories: [
        { categoryId: 'plumbing', subcategoryIds: ['tap', 'pipe'] },
        { categoryId: 'electrical', subcategoryIds: ['fan'] },
      ],
      skillTags: ['plumbing', 'Tap Repair', 'pipe fitting', 'electrical'],
    });
  });

  it('ignores ids that are not in the active catalog', () => {
    expect(deriveSelection(['gone'], CATALOG)).toEqual({ categories: [], skillTags: [] });
  });
});

describe('buildServicesSchema', () => {
  const schema = buildServicesSchema(CATALOG);
  const valid = { displayName: 'Ravi', yearsExperience: '6', subcategoryIds: ['tap'] };

  function messages(values: unknown): string[] {
    const result = schema.safeParse(values);
    return result.success ? [] : result.error.issues.map((issue) => issue.message);
  }

  it('accepts a valid profile', () => {
    expect(messages(valid)).toEqual([]);
  });

  it('requires at least one service', () => {
    expect(messages({ ...valid, subcategoryIds: [] })).toEqual([
      'Pick at least one service you offer',
    ]);
  });

  it('rejects services that carry no skills', () => {
    expect(messages({ ...valid, subcategoryIds: ['untagged'] })[0]).toMatch(/no skills/);
  });

  it('checks years of experience is a whole number from 0 to 50', () => {
    expect(messages({ ...valid, yearsExperience: '0' })).toEqual([]);
    expect(messages({ ...valid, yearsExperience: '50' })).toEqual([]);
    expect(messages({ ...valid, yearsExperience: '51' })).toHaveLength(1);
    expect(messages({ ...valid, yearsExperience: '2.5' })).toHaveLength(1);
    expect(messages({ ...valid, yearsExperience: '' })).toHaveLength(1);
  });

  it('limits the display name to 100 characters', () => {
    expect(messages({ ...valid, displayName: 'a'.repeat(101) })).toHaveLength(1);
  });

  it('allows at most 5 categories', () => {
    const many = Array.from({ length: 6 }, (_, i) =>
      category(`c${i}`, [{ id: `s${i}`, tags: [`t${i}`] }]),
    );
    const result = buildServicesSchema(many).safeParse({
      ...valid,
      subcategoryIds: many.map((_, i) => `s${i}`),
    });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.message).toMatch(/at most 5 categories/);
  });

  it('allows at most 20 distinct skills', () => {
    const wide = [
      category('wide', [
        { id: 'a', tags: Array.from({ length: 12 }, (_, i) => `a${i}`) },
        { id: 'b', tags: Array.from({ length: 9 }, (_, i) => `b${i}`) },
      ]),
    ];
    const result = buildServicesSchema(wide).safeParse({ ...valid, subcategoryIds: ['a', 'b'] });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.message).toMatch(/21 different skills/);
  });
});

describe('servicesFormDefaults', () => {
  it('starts a new profile from the account name with nothing chosen', () => {
    expect(servicesFormDefaults(null, CATALOG, 'Asha')).toEqual({
      values: { displayName: 'Asha', yearsExperience: '', subcategoryIds: [] },
      droppedCount: 0,
    });
  });

  it('drops saved services that are no longer offered', () => {
    const profile = {
      ...PROFILE,
      categories: [{ categoryId: 'plumbing', subcategoryIds: ['tap', 'retired'] }],
    };
    expect(servicesFormDefaults(profile, CATALOG)).toEqual({
      values: { displayName: 'Ravi Kumar', yearsExperience: '6', subcategoryIds: ['tap'] },
      droppedCount: 1,
    });
  });
});

describe('toProfilePayload', () => {
  const values = { displayName: '  ', yearsExperience: ' 7 ', subcategoryIds: ['fan'] };

  it('derives categories and tags, keeps the radius, and leaves the location out', () => {
    expect(toProfilePayload(values, CATALOG, PROFILE)).toEqual({
      displayName: null,
      categories: [{ categoryId: 'electrical', subcategoryIds: ['fan'] }],
      skillTags: ['electrical'],
      yearsExperience: 7,
      serviceRadiusKm: 15,
    });
  });

  it('proposes the default radius for a new profile', () => {
    expect(toProfilePayload(values, CATALOG, null).serviceRadiusKm).toBe(10);
  });
});

describe('serviceAreaSchema', () => {
  const valid = { latitude: '12.9716', longitude: '77.5946', serviceRadiusKm: 10 };

  it('accepts coordinates and a radius in range', () => {
    expect(serviceAreaSchema.safeParse(valid).success).toBe(true);
    expect(
      serviceAreaSchema.safeParse({ latitude: '-90', longitude: '180', serviceRadiusKm: 100 })
        .success,
    ).toBe(true);
  });

  it.each([
    [{ latitude: '' }, 'latitude'],
    [{ latitude: '91' }, 'latitude'],
    [{ latitude: 'north' }, 'latitude'],
    [{ longitude: '-180.5' }, 'longitude'],
    [{ serviceRadiusKm: 0 }, 'serviceRadiusKm'],
    [{ serviceRadiusKm: 101 }, 'serviceRadiusKm'],
  ])('rejects %o', (override, field) => {
    const result = serviceAreaSchema.safeParse({ ...valid, ...override });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.path).toEqual([field]);
  });
});

describe('toServiceAreaChange', () => {
  it('uses the radius endpoint when the location is unchanged', () => {
    expect(
      toServiceAreaChange(
        { latitude: '12.971600', longitude: '77.5946', serviceRadiusKm: 25 },
        PROFILE,
      ),
    ).toEqual({ kind: 'radius', serviceRadiusKm: 25 });
  });

  it('re-sends the profile with the new location when it moved', () => {
    expect(
      toServiceAreaChange(
        { latitude: '13.0827', longitude: '80.2707', serviceRadiusKm: 5 },
        PROFILE,
      ),
    ).toEqual({
      kind: 'profile',
      payload: {
        displayName: 'Ravi Kumar',
        categories: PROFILE.categories,
        skillTags: PROFILE.skillTags,
        yearsExperience: 6,
        serviceRadiusKm: 5,
        baseLatitude: 13.0827,
        baseLongitude: 80.2707,
      },
    });
  });

  it('sets a first location through the profile endpoint', () => {
    const change = toServiceAreaChange(
      { latitude: '12.9716', longitude: '77.5946', serviceRadiusKm: 10 },
      { ...PROFILE, baseLatitude: null, baseLongitude: null },
    );
    expect(change.kind).toBe('profile');
  });
});

describe('availabilitySchema', () => {
  const monday = { dayOfWeek: 'MONDAY' as const, startHour: 9, endHour: 13 };

  function paths(values: unknown): string[] {
    const result = availabilitySchema.safeParse(values);
    return result.success ? [] : result.error.issues.map((issue) => issue.path.join('.'));
  }

  it('accepts "any time" whatever hidden ranges are left over', () => {
    expect(paths({ mode: 'anytime', slots: [{ ...monday, endHour: 9 }] })).toEqual([]);
  });

  it('requires at least one range for weekly hours', () => {
    expect(paths({ mode: 'weekly', slots: [] })).toEqual(['slots']);
  });

  it('requires the end after the start', () => {
    expect(paths({ mode: 'weekly', slots: [{ ...monday, endHour: 9 }] })).toEqual([
      'slots.0.endHour',
    ]);
  });

  it('rejects overlapping ranges on the same day but allows touching ones', () => {
    expect(
      paths({ mode: 'weekly', slots: [monday, { ...monday, startHour: 12, endHour: 15 }] }),
    ).toEqual(['slots.1.startHour']);
    expect(
      paths({ mode: 'weekly', slots: [monday, { ...monday, startHour: 13, endHour: 24 }] }),
    ).toEqual([]);
    expect(paths({ mode: 'weekly', slots: [monday, { ...monday, dayOfWeek: 'TUESDAY' }] })).toEqual(
      [],
    );
  });
});

describe('toAvailabilityPayload', () => {
  it('sends no slots for "any time"', () => {
    expect(
      toAvailabilityPayload({
        mode: 'anytime',
        slots: [{ dayOfWeek: 'MONDAY', startHour: 9, endHour: 17 }],
      }),
    ).toEqual([]);
  });

  it('sends weekly slots Monday first, then by start time', () => {
    expect(
      toAvailabilityPayload({
        mode: 'weekly',
        slots: [
          { dayOfWeek: 'SUNDAY', startHour: 10, endHour: 12 },
          { dayOfWeek: 'MONDAY', startHour: 14, endHour: 18 },
          { dayOfWeek: 'MONDAY', startHour: 8, endHour: 12 },
        ],
      }),
    ).toEqual([
      { dayOfWeek: 'MONDAY', startHour: 8, endHour: 12 },
      { dayOfWeek: 'MONDAY', startHour: 14, endHour: 18 },
      { dayOfWeek: 'SUNDAY', startHour: 10, endHour: 12 },
    ]);
  });
});
