import { useRef } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  ImageList,
  ImageListItem,
  Stack,
  Typography,
} from '@mui/material';
import AddAPhotoRoundedIcon from '@mui/icons-material/AddAPhotoRounded';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import { isApiError } from '@api/client';
import type { JobPhoto, PhotoKind } from './api';
import { useUploadJobPhotos } from './hooks';

interface PhotoUploadCardProps {
  bookingId: string;
  kind: PhotoKind;
  title: string;
  /** Explains why the photo is required (Requirement 11.2 / 11.4). */
  helperText: string;
  /** Photos already attached to the booking, filtered to this kind by the parent. */
  photos: JobPhoto[];
  /** Whether uploads are currently allowed (based on job state). */
  disabled?: boolean;
}

/**
 * Reusable card for uploading and previewing before/after photos. Enforces
 * image-only selection at the input level; the Booking Service is the source of
 * truth for the "at least one photo" rule (Requirement 11.2, 11.4).
 */
export function PhotoUploadCard({
  bookingId,
  kind,
  title,
  helperText,
  photos,
  disabled = false,
}: PhotoUploadCardProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const upload = useUploadJobPhotos(bookingId);

  const handleFiles = (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return;
    upload.mutate({ kind, files: Array.from(fileList) });
    if (inputRef.current) inputRef.current.value = '';
  };

  const hasPhotos = photos.length > 0;

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
          <Typography variant="subtitle2" fontWeight={700}>
            {title}
          </Typography>
          {hasPhotos ? (
            <CheckCircleRoundedIcon color="success" fontSize="small" aria-label="Photo attached" />
          ) : null}
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          {helperText}
        </Typography>

        {hasPhotos ? (
          <ImageList cols={3} gap={8} sx={{ mt: 1.5, mb: 0 }}>
            {photos.map((photo) => (
              <ImageListItem key={photo.id}>
                <Box
                  component="img"
                  src={photo.url}
                  alt={`${kind === 'BEFORE' ? 'Before' : 'After'} photo`}
                  loading="lazy"
                  sx={{ borderRadius: 1, aspectRatio: '1 / 1', objectFit: 'cover' }}
                />
              </ImageListItem>
            ))}
          </ImageList>
        ) : null}

        {upload.isError ? (
          <Alert severity="error" sx={{ mt: 1.5 }}>
            {isApiError(upload.error) ? upload.error.message : 'Photo upload failed. Try again.'}
          </Alert>
        ) : null}

        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          multiple
          hidden
          onChange={(event) => handleFiles(event.target.files)}
        />
        <Button
          variant={hasPhotos ? 'outlined' : 'contained'}
          startIcon={<AddAPhotoRoundedIcon />}
          onClick={() => inputRef.current?.click()}
          disabled={disabled || upload.isPending}
          sx={{ mt: 1.5 }}
          fullWidth
        >
          {upload.isPending ? 'Uploading…' : hasPhotos ? 'Add another photo' : 'Upload photo'}
        </Button>
      </CardContent>
    </Card>
  );
}
