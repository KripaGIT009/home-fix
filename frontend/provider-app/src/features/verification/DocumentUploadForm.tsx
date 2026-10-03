import { useRef, useState } from 'react';
import { Alert, Box, Button, Card, CardContent, Stack, Typography } from '@mui/material';
import AddAPhotoRoundedIcon from '@mui/icons-material/AddAPhotoRounded';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import DescriptionRoundedIcon from '@mui/icons-material/DescriptionRounded';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import { env } from '@config/env';
import { formatDateTime } from '@lib/format';
import { DOCUMENT_TYPES, type DocumentType, type RequiredDocument } from './api';
import { useSubmitVerificationDocuments } from './hooks';
import { documentHint, documentLabel, uploadErrorMessage } from './status';

/** Images (phone photos, scans) and PDFs are accepted for every document. */
const ACCEPT = 'image/*,application/pdf';

type Selection = Record<DocumentType, File | null>;

const EMPTY_SELECTION: Selection = {
  GOVERNMENT_ID: null,
  ADDRESS_PROOF: null,
  SKILL_CERTIFICATION: null,
};

interface DocumentUploadFormProps {
  /** What is already on file, so a resubmission shows what it replaces. */
  documents: RequiredDocument[];
}

/**
 * Document submission (Requirement 5.3): one picker per required document,
 * sent together as one multipart request because the Verification Service
 * needs all three at once. Selecting is local; nothing is sent until "Send".
 */
export function DocumentUploadForm({ documents }: DocumentUploadFormProps) {
  const [selection, setSelection] = useState<Selection>(EMPTY_SELECTION);
  const [sent, setSent] = useState(false);
  const submit = useSubmitVerificationDocuments();

  const missing = DOCUMENT_TYPES.filter((type) => !selection[type]);
  const complete = missing.length === 0;

  const choose = (type: DocumentType, file: File | null) => {
    setSent(false);
    submit.reset();
    setSelection((current) => ({ ...current, [type]: file }));
  };

  const handleSubmit = () => {
    const { GOVERNMENT_ID, ADDRESS_PROOF, SKILL_CERTIFICATION } = selection;
    if (!GOVERNMENT_ID || !ADDRESS_PROOF || !SKILL_CERTIFICATION) return;
    submit.mutate(
      { GOVERNMENT_ID, ADDRESS_PROOF, SKILL_CERTIFICATION },
      {
        onSuccess: () => {
          setSelection(EMPTY_SELECTION);
          setSent(true);
        },
      },
    );
  };

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle2" fontWeight={700}>
          Upload your documents
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          Choose a clear photo or PDF for each document, then send all three together.
        </Typography>

        <Stack spacing={1.5} sx={{ mt: 2 }}>
          {DOCUMENT_TYPES.map((type) => (
            <DocumentPicker
              key={type}
              type={type}
              file={selection[type]}
              onFile={(file) => choose(type, file)}
              existing={documents.find((doc) => doc.type === type)}
              disabled={submit.isPending}
            />
          ))}
        </Stack>

        {submit.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {uploadErrorMessage(submit.error)}
          </Alert>
        ) : null}
        {sent ? (
          <Alert severity="success" sx={{ mt: 2 }}>
            Documents sent. We will review them and update your status here.
          </Alert>
        ) : null}

        <Button
          variant="contained"
          size="large"
          fullWidth
          sx={{ mt: 2 }}
          disabled={!complete || submit.isPending}
          onClick={handleSubmit}
        >
          {submit.isPending ? 'Sending…' : 'Send documents'}
        </Button>
        {!complete && !submit.isPending ? (
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
            Still needed: {missing.map(documentLabel).join(', ')}.
          </Typography>
        ) : null}
      </CardContent>
    </Card>
  );
}

interface DocumentPickerProps {
  type: DocumentType;
  file: File | null;
  onFile: (file: File | null) => void;
  /** The document of this type already on file, if any. */
  existing: RequiredDocument | undefined;
  disabled: boolean;
}

/**
 * One document's picker. As in the job photo card, the Android and iOS apps
 * get a second "Take photo" button whose input is marked `capture`, because
 * Android's file chooser offers no camera for a plain file input; the web keeps
 * a single picker so mobile browsers still offer both camera and files.
 */
function DocumentPicker({ type, file, onFile, existing, disabled }: DocumentPickerProps) {
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const label = documentLabel(type);

  const handleFiles = (fileList: FileList | null) => {
    const picked = fileList?.[0];
    if (picked) onFile(picked);
    if (fileRef.current) fileRef.current.value = '';
    if (cameraRef.current) cameraRef.current.value = '';
  };

  return (
    <Box
      sx={{
        border: 1,
        borderColor: file ? 'success.light' : 'divider',
        borderRadius: 2,
        p: 1.5,
      }}
    >
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="subtitle2">{label}</Typography>
          <Typography variant="caption" color="text.secondary" component="p">
            {documentHint(type)}
          </Typography>
          {existing?.submitted && !file ? (
            <Typography variant="caption" color="text.secondary" component="p">
              Sent earlier
              {existing.uploadedAt ? ` on ${formatDateTime(existing.uploadedAt)}` : ''}
            </Typography>
          ) : null}
        </Box>
        {file ? (
          <CheckCircleRoundedIcon color="success" fontSize="small" aria-label={`${label} chosen`} />
        ) : null}
      </Stack>

      {file ? (
        <Stack direction="row" alignItems="center" spacing={1} sx={{ mt: 1 }}>
          <DescriptionRoundedIcon fontSize="small" color="action" aria-hidden />
          <Typography variant="body2" noWrap sx={{ flexGrow: 1, minWidth: 0 }} title={file.name}>
            {file.name}
          </Typography>
          <Button
            size="small"
            color="inherit"
            startIcon={<CloseRoundedIcon />}
            onClick={() => onFile(null)}
            disabled={disabled}
            aria-label={`Remove ${label}`}
          >
            Remove
          </Button>
        </Stack>
      ) : null}

      <input
        ref={fileRef}
        type="file"
        accept={ACCEPT}
        hidden
        aria-label={`${label} file`}
        onChange={(event) => handleFiles(event.target.files)}
      />
      {env.isNative ? (
        <>
          <input
            ref={cameraRef}
            type="file"
            accept="image/*"
            capture="environment"
            hidden
            aria-label={`${label} photo`}
            onChange={(event) => handleFiles(event.target.files)}
          />
          <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
            <Button
              size="small"
              variant="outlined"
              startIcon={<AddAPhotoRoundedIcon />}
              onClick={() => cameraRef.current?.click()}
              disabled={disabled}
              fullWidth
            >
              Take photo
            </Button>
            <Button
              size="small"
              variant="outlined"
              startIcon={<UploadFileRoundedIcon />}
              onClick={() => fileRef.current?.click()}
              disabled={disabled}
              fullWidth
            >
              {file ? 'Change file' : 'Choose file'}
            </Button>
          </Stack>
        </>
      ) : (
        <Button
          size="small"
          variant="outlined"
          startIcon={<UploadFileRoundedIcon />}
          onClick={() => fileRef.current?.click()}
          disabled={disabled}
          sx={{ mt: 1 }}
        >
          {file ? 'Change file' : 'Choose file'}
        </Button>
      )}
    </Box>
  );
}
