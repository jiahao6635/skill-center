import { describe, expect, it } from 'vitest'
import * as mod from './upload-zone.tsx'

/**
 * upload-zone.tsx exports the UploadZone component. Size-limit constants
 * live in package-limits.ts so this module stays a dropzone wrapper.
 *
 * We verify the export contract so downstream consumers break fast if
 * the module shape changes.
 */
describe('upload-zone module exports', () => {
  it('exports the UploadZone component', () => {
    expect(mod.UploadZone).toBeDefined()
    expect(typeof mod.UploadZone).toBe('function')
  })
})
