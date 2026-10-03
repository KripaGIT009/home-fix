import { useCallback, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material';
import AddPhotoAlternateRoundedIcon from '@mui/icons-material/AddPhotoAlternateRounded';
import PhotoCameraRoundedIcon from '@mui/icons-material/PhotoCameraRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import { env } from '@config/env';
import {
  ACCEPTED_MEDIA_LABEL,
  MAX_MEDIA_FILES,
  MAX_MEDIA_FILE_MB,
  MEDIA_ACCEPT_ATTR,
} from './constants';
import { formatFileSize, mergeMediaSelection } from './media';

interface MediaUploadProps {
  files: File[];
  onChange: (files: File[]) => void;
}

/**
 * Media upload control for the Service Request screen (Requirement 7.2).
 *
 * Enforces the 10-file cap and validates each file's type (JPEG/PNG/MP4/MOV)
 * and size (≤ 50 MB), surfacing a descriptive error for any rejected file. The
 * accepted files are lifted to the parent form as a File[].
 *
 * In the Android and iOS apps a "Take a photo" button opens the camera directly
 * (Requirement MT-14.4): Android's file chooser offers no camera for a plain
 * file input, while one marked `capture` makes the WebView launch the camera
 * and ask for the camera permission. The web app is unchanged, since `capture`
 * would take the gallery choice away from mobile browsers.
 */
export function MediaUpload({ files, onChange }: MediaUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const [rejections, setRejections] = useState<string[]>([]);

  const handleSelect = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const incoming = Array.from(event.target.files ?? []);
      // Allow re-selecting the same file(s) later by resetting the input value.
      event.target.value = '';
      if (incoming.length === 0) return;

      const { accepted, rejected } = mergeMediaSelection(files, incoming);
      onChange(accepted);
      setRejections(rejected.map((r) => `${r.file.name}: ${r.error}`));
    },
    [files, onChange],
  );

  const handleRemove = useCallback(
    (index: number) => {
      const next = files.filter((_, i) => i !== index);
      onChange(next);
    },
    [files, onChange],
  );

  const atLimit = files.length >= MAX_MEDIA_FILES;

  return (
    <Stack spacing={1}>
      <Box>
        <Typography variant="subtitle2">Photos or videos (optional)</Typography>
        <Typography variant="caption" color="text.secondary">
          Up to {MAX_MEDIA_FILES} files. {ACCEPTED_MEDIA_LABEL}, max {MAX_MEDIA_FILE_MB} MB each.
        </Typography>
      </Box>

      <input
        ref={inputRef}
        type="file"
        multiple
        accept={MEDIA_ACCEPT_ATTR}
        onChange={handleSelect}
        style={{ display: 'none' }}
        aria-hidden
      />
      <Button
        type="button"
        variant="outlined"
        startIcon={<AddPhotoAlternateRoundedIcon />}
        onClick={() => inputRef.current?.click()}
        disabled={atLimit}
        fullWidth
        sx={{
          py: 2,
          borderStyle: 'dashed',
          borderWidth: 1.5,
          color: 'primary.main',
          '&:hover': { borderStyle: 'dashed', borderWidth: 1.5 },
        }}
      >
        {atLimit ? `Maximum ${MAX_MEDIA_FILES} files added` : 'Add photos or videos'}
      </Button>
      {env.isNative ? (
        <>
          <input
            ref={cameraRef}
            type="file"
            accept="image/*"
            capture="environment"
            onChange={handleSelect}
            style={{ display: 'none' }}
            aria-hidden
          />
          <Button
            type="button"
            variant="text"
            startIcon={<PhotoCameraRoundedIcon />}
            onClick={() => cameraRef.current?.click()}
            disabled={atLimit}
            fullWidth
          >
            Take a photo
          </Button>
        </>
      ) : null}

      {rejections.length > 0 ? (
        <Alert severity="warning" onClose={() => setRejections([])}>
          <Stack spacing={0.5}>
            {rejections.map((message) => (
              <span key={message}>{message}</span>
            ))}
          </Stack>
        </Alert>
      ) : null}

      {files.length > 0 ? (
        <List dense disablePadding>
          {files.map((file, index) => (
            <ListItem
              key={`${file.name}-${file.size}`}
              disableGutters
              secondaryAction={
                <IconButton
                  edge="end"
                  aria-label={`Remove ${file.name}`}
                  onClick={() => handleRemove(index)}
                >
                  <DeleteOutlineRoundedIcon />
                </IconButton>
              }
            >
              <ListItemText primary={file.name} secondary={formatFileSize(file.size)} />
            </ListItem>
          ))}
        </List>
      ) : null}
    </Stack>
  );
}
