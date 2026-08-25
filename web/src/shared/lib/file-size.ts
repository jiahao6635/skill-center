const BYTES_PER_KB = 1024
const BYTES_PER_MB = 1024 * 1024
const BYTES_PER_GB = 1024 * 1024 * 1024

export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B'
  }
  if (bytes < BYTES_PER_KB) {
    return `${Math.round(bytes)} B`
  }
  if (bytes < BYTES_PER_MB) {
    return `${formatSizeNumber(bytes / BYTES_PER_KB)} KB`
  }
  if (bytes < BYTES_PER_GB) {
    return `${formatSizeNumber(bytes / BYTES_PER_MB)} MB`
  }
  return `${formatSizeNumber(bytes / BYTES_PER_GB)} GB`
}

function formatSizeNumber(value: number): string {
  const formatted = value.toFixed(1)
  return formatted.endsWith('.0') ? formatted.slice(0, -2) : formatted
}
