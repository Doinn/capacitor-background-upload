# @doinn/capacitor-background-upload

Native background file upload for Capacitor 7. Uploads via `PUT` to any URL with custom headers.

- **iOS**: `URLSession` background sessions — uploads survive app suspension and termination.
- **Android**: `WorkManager` + OkHttp with a foreground service notification — uploads survive process death and, on GCS resumable sessions, resume from the committed offset.

Built for direct-to-GCS uploads via signed URLs (single `PUT` or resumable sessions), but works with any endpoint that accepts a plain `PUT`.

## Install

```bash
yarn add @doinn/capacitor-background-upload@https://github.com/Doinn/capacitor-background-upload.git#v1.0.0
npx cap sync
```

## API

```js
import { BackgroundUpload } from '@doinn/capacitor-background-upload'
```

### `prepareFile(options)`

Copies the source into the plugin's cache dir (resolving `content://` URIs on Android), optionally downscaling/recompressing images, so the upload worker owns a stable file path.

```js
const { path, size, mimeType } = await BackgroundUpload.prepareFile({
  filePath,           // string, required — file path or content:// URI
  imageMaxDimension,  // number, optional — downscale longest image side
  imageQuality,       // number 0..1, optional — JPEG recompression quality
  deleteSource        // boolean, default false — delete/move the original after copy
})
```

### `enqueueUpload(options)`

Queues a background upload. Resolves when the job is enqueued, not when it finishes — listen to events for completion.

```js
await BackgroundUpload.enqueueUpload({
  jobId,             // string, required — unique id, used for dedup (KEEP policy)
  filePath,          // string, required — path returned by prepareFile
  url,               // string, required — signed upload URL
  headers,           // object, optional — extra request headers
  notificationTitle  // string, optional (Android) — foreground notification title
})
```

### `getJobs()`

Returns all persisted jobs: `{ jobs: [{ jobId, state, bytesSent, totalBytes, httpStatus?, error? }] }`. `state` is `queued | uploading | uploaded | failed`. Used for boot reconciliation after app restart.

### `acknowledge({ jobId })`

Removes a terminal job from the store and deletes its prepared file from the cache dir. Call after the app has confirmed the upload with the backend.

### `cancel({ jobId })`

Cancels the work and marks the job `failed` with error `cancelled`.

### `cleanupOrphans({ maxAgeMs? })`

Deletes prepared files in the cache dir no longer referenced by any job and older than `maxAgeMs` (default 24h). Returns `{ deleted }`.

### Events

```js
BackgroundUpload.addListener('uploadProgress', ({ jobId, bytesSent, totalBytes }) => {})
BackgroundUpload.addListener('uploadCompleted', ({ jobId, httpStatus }) => {})
BackgroundUpload.addListener('uploadFailed', ({ jobId, httpStatus, error }) => {})
```

Terminal events are also replayed on app start for jobs that finished while the app was dead — reconcile with `getJobs()` on boot.

## Release

```bash
yarn release:patch   # or release:minor / release:major
```

Bumps the version, pushes, and pushes the `vX.Y.Z` tag. Consumers pin the tag in their dependency URL.

## License

MIT
