import { QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { ErrorBoundary } from 'react-error-boundary';
import { RouterProvider } from 'react-router-dom';
import { ErrorFallback } from '@components/ErrorFallback';
import { queryClient } from '@lib/queryClient';
import { theme } from '@lib/theme';
import { router } from './router';
import { env } from '@config/env';

export default function App() {
  return (
    <ErrorBoundary FallbackComponent={ErrorFallback} onReset={() => queryClient.clear()}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider theme={theme}>
          <CssBaseline />
          <RouterProvider router={router} />
          {env.isProduction ? null : <ReactQueryDevtools initialIsOpen={false} />}
        </ThemeProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
