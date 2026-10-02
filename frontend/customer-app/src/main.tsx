import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { assertRuntimeConfig } from '@config/env';
import '@fontsource-variable/plus-jakarta-sans';
import App from './App';
import './index.css';

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('Root element #root not found');
}

/**
 * Render a misconfiguration as readable text in the app itself.
 *
 * On a device there is no console to glance at, and a native build pointed at a
 * relative API URL otherwise looks like a working app whose every screen fails
 * to load. Showing the reason on screen — and rethrowing so it also reaches
 * logcat/Safari — is what makes the failure loud.
 */
function renderStartupError(rootEl: HTMLElement, error: unknown): never {
  const message = error instanceof Error ? error.message : String(error);
  const pre = document.createElement('pre');
  pre.setAttribute(
    'style',
    'margin:0;padding:24px;font:14px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace;' +
      'white-space:pre-wrap;word-break:break-word;color:#7F1D1D;background:#FEF2F2;min-height:100vh',
  );
  pre.textContent = `Configuration error\n\n${message}`;
  rootEl.replaceChildren(pre);
  throw error;
}

try {
  assertRuntimeConfig();
} catch (error) {
  renderStartupError(rootElement, error);
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
