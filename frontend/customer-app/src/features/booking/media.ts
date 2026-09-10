import {
  ACCEPTED_MEDIA_LABEL,
  ACCEPTED_MEDIA_MIME_TYPES,
  MAX_MEDIA_FILE_BYTES,
  MAX_MEDIA_FILE_MB,
  MAX_MEDIA_FILES,
} from './constants';

/** Result of validating a single media file (Requirement 7.2). */
export interface MediaValidationResult {
  file: File;
  valid: boolean;
  /** Populated when the file is rejected. */
  error?: string;
}

const acceptedMimeSet = new Set<string>(ACCEPTED_MEDIA_MIME_TYPES);

/** True when the file's MIME type is one of the accepted media formats. */
export function isAcceptedMediaType(file: File): boolean {
  return acceptedMimeSet.has(file.type);
}

/** True when the file is within the per-file size limit (50 MB). */
export function isWithinSizeLimit(file: File): boolean {
  return file.size <= MAX_MEDIA_FILE_BYTES;
}

/** Format bytes into a compact MB string for display. */
export function formatFileSize(bytes: number): string {
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(mb >= 10 ? 0 : 1)} MB`;
}

/**
 * Validate a single file against the media type and size rules. Returns a
 * descriptive error identifying which constraint was violated (Requirement 7.2).
 */
export function validateMediaFile(file: File): MediaValidationResult {
  if (!isAcceptedMediaType(file)) {
    return {
      file,
      valid: false,
      error: `Unsupported format. Allowed: ${ACCEPTED_MEDIA_LABEL}.`,
    };
  }
  if (!isWithinSizeLimit(file)) {
    return {
      file,
      valid: false,
      error: `File is too large (${formatFileSize(file.size)}). Max ${MAX_MEDIA_FILE_MB} MB.`,
    };
  }
  return { file, valid: true };
}

export interface MediaSelectionOutcome {
  /** Files accepted and merged into the selection. */
  accepted: File[];
  /** Files rejected during this selection, with reasons. */
  rejected: MediaValidationResult[];
}

/**
 * Merge newly selected files into the existing selection, enforcing the type,
 * size, and count limits (Requirement 7.2). Files beyond the 10-file cap are
 * rejected with an explanatory error, and exact duplicates (same name + size)
 * are silently skipped so re-selecting the same file is idempotent.
 */
export function mergeMediaSelection(existing: File[], incoming: File[]): MediaSelectionOutcome {
  const accepted: File[] = [...existing];
  const rejected: MediaValidationResult[] = [];

  for (const file of incoming) {
    const isDuplicate = accepted.some(
      (current) => current.name === file.name && current.size === file.size,
    );
    if (isDuplicate) {
      continue;
    }

    const result = validateMediaFile(file);
    if (!result.valid) {
      rejected.push(result);
      continue;
    }

    if (accepted.length >= MAX_MEDIA_FILES) {
      rejected.push({
        file,
        valid: false,
        error: `You can attach up to ${MAX_MEDIA_FILES} files.`,
      });
      continue;
    }

    accepted.push(file);
  }

  return { accepted, rejected };
}
