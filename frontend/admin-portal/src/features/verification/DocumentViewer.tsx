import { useState } from 'react';
import { Box, Stack, Tab, Tabs, Typography } from '@mui/material';
import type { VerificationDocument } from './api';

interface DocumentViewerProps {
  documents: readonly VerificationDocument[];
}

/** True when the content type is a PDF the browser can render in an iframe. */
function isPdf(contentType: string): boolean {
  return contentType.toLowerCase().includes('pdf');
}

/** True when the content type is a browser-renderable image. */
function isImage(contentType: string): boolean {
  return contentType.toLowerCase().startsWith('image/');
}

/**
 * Sandbox for the document frames. These files are uploaded by providers and
 * served from storage, so they are untrusted content rendered inside an admin
 * session: the sandbox withholds forms, popups, top-level navigation, downloads
 * and plugin access. `allow-scripts`/`allow-same-origin` are what the browser's
 * built-in PDF viewer needs to render at all; together they would let framed
 * *same-origin* content lift its own sandbox, which does not apply here because
 * documents are served from the storage origin, not the portal's.
 */
const DOCUMENT_SANDBOX = 'allow-same-origin allow-scripts';

/**
 * Inline document viewer (Requirement 19.3). Renders each submitted document
 * inside the dashboard without a separate download: PDFs are embedded in an
 * <iframe> (which uses the browser's native PDF renderer) and images in an
 * <img>. A tab strip switches between documents when a provider submitted more
 * than one.
 */
export function DocumentViewer({ documents }: DocumentViewerProps) {
  const [active, setActive] = useState(0);

  if (documents.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
        No documents were submitted.
      </Typography>
    );
  }

  const current = documents[Math.min(active, documents.length - 1)];
  if (!current) return null;

  return (
    <Stack spacing={1.5}>
      {documents.length > 1 ? (
        <Tabs
          value={active}
          onChange={(_event, value: number) => setActive(value)}
          variant="scrollable"
          scrollButtons="auto"
          aria-label="Submitted documents"
        >
          {documents.map((doc, index) => (
            <Tab key={doc.id} label={doc.type} id={`doc-tab-${index}`} />
          ))}
        </Tabs>
      ) : (
        <Typography variant="subtitle2">{current.type}</Typography>
      )}

      <Box
        sx={{
          border: (t) => `1px solid ${t.palette.divider}`,
          borderRadius: 1,
          overflow: 'hidden',
          bgcolor: 'grey.100',
          minHeight: 480,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {isPdf(current.contentType) ? (
          <Box
            component="iframe"
            src={current.url}
            title={`${current.type} — ${current.fileName}`}
            sandbox={DOCUMENT_SANDBOX}
            sx={{ width: '100%', height: 560, border: 0 }}
          />
        ) : isImage(current.contentType) ? (
          <Box
            component="img"
            src={current.url}
            alt={`${current.type} — ${current.fileName}`}
            sx={{ maxWidth: '100%', maxHeight: 560, objectFit: 'contain' }}
          />
        ) : (
          <Box
            component="iframe"
            src={current.url}
            title={`${current.type} — ${current.fileName}`}
            sandbox={DOCUMENT_SANDBOX}
            sx={{ width: '100%', height: 560, border: 0 }}
          />
        )}
      </Box>
      <Typography variant="caption" color="text.secondary">
        {current.fileName}
      </Typography>
    </Stack>
  );
}
