/**
 * Hand a downloaded file to the browser's save flow. The object URL is revoked
 * on the next tick, once the click has started the download.
 */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * The file name from a Content-Disposition header, preferring the RFC 5987
 * `filename*=UTF-8''…` form over plain `filename="…"`. Null when absent.
 */
export function fileNameFromDisposition(header: string | null | undefined): string | null {
  if (!header) return null;
  const extended = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header);
  if (extended?.[1]) {
    try {
      return decodeURIComponent(extended[1].trim());
    } catch {
      // Malformed encoding: try the plain form below.
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/.exec(header);
  return plain?.[1]?.trim() || null;
}
