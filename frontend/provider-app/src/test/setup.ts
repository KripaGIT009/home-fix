import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// Without vitest globals, testing-library cannot register its own cleanup.
afterEach(() => {
  cleanup();
});
