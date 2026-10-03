/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_AUTH_BASE_URL?: string;
  readonly VITE_REALTIME_BASE_URL?: string;
  readonly VITE_API_GATEWAY_URL?: string;
  readonly VITE_AUTH_SERVICE_URL?: string;
  readonly VITE_GOOGLE_CLIENT_ID?: string;
  readonly VITE_PROVIDER_APP_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
