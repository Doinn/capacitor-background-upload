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

`mimeType` is omitted when it cannot be determined.

### `discardPreparedFile({ path })` (Android only)

Deletes a file returned by `prepareFile` that was never enqueued. A path outside the plugin's cache dir is ignored. Resolves with no value.

### `enqueueUpload(options)`

Queues a background upload. Resolves when the job is enqueued, not when it finishes — listen to events for completion.

Enqueue each `jobId` once. On Android the job record is reset (`attempts` 0, `state` `queued`) before the work is enqueued with the KEEP policy, so enqueueing a `jobId` that already has work resets the record under that work. Use a new `jobId` for a new upload.

```js
await BackgroundUpload.enqueueUpload({
  jobId,             // string, required — unique id, enqueue each jobId only once
  filePath,          // string, required — path returned by prepareFile
  url,               // string, required — signed upload URL
  headers,           // object, optional — extra request headers
  notificationTitle  // string, optional (Android) — foreground notification title
})
```

### `getJobs()`

Returns all persisted jobs. This is the reliable way to learn the state of a job while the app runs. On Android, work left over from an earlier start of the app is cleared by `clearAll`, not reconciled through this call.

```js
const { jobs } = await BackgroundUpload.getJobs()
// jobs: [{ jobId, state, bytesSent, totalBytes, httpStatus?, error?, attempts, createdAt }]
```

- `attempts` (Android only): number of counted attempts so far.
- `createdAt` (Android only): time the job was enqueued, in milliseconds since the epoch.

On iOS each job has only `jobId`, `state`, `bytesSent`, `totalBytes`, `httpStatus` and `error`.

### `acknowledge({ jobId })`

Cancels any pending work for the job, removes its record from the store and deletes its prepared file from the cache dir. Call it after the app has confirmed the upload with the backend, or to drop a job that is no longer wanted.

On iOS it removes the record and deletes the prepared file but does not cancel a task that is still running.

### `cancel({ jobId })`

Marks the job `failed` with the error `cancelled` and cancels its work. A job that is already `uploaded` keeps that state. A transfer whose last bytes were already sent can still finish as `uploaded` after the call; the app must treat that upload as unconfirmed and verify it with the backend. On Android this is the only way a job ends as `failed` with the error `cancelled`.

On iOS it only cancels the running `URLSession` task. The record then fails through the normal completion path with the error message the system reports for a cancelled request, and `uploadFailed` is emitted.

### `cleanupOrphans({ maxAgeMs? })`

Deletes prepared files in the cache dir no longer referenced by any job and older than `maxAgeMs` (default 24h). Returns `{ deleted }`.

### `clearAll()` (Android only)

Meant to be called once per JavaScript start, before any upload. It removes every job record, cancels all pending work and deletes every file in the plugin's cache dir. Resolves with:

```js
{ cancelled, deletedFiles, oldestAgeMs }
```

`cancelled` is the number of job records removed, `deletedFiles` the number of files deleted, and `oldestAgeMs` the age of the oldest removed job, or `null` when there was none.

### Job states

| State | Meaning |
| --- | --- |
| `queued` | Not transferring. This covers a job that has not started, one waiting for the network, and one waiting between attempts. |
| `uploading` | A transfer is running. |
| `uploaded` | The server answered with a 2xx status. On Android it can also be reached with `httpStatus` 308, when the resume probe finds the whole file already committed. |
| `failed` | The job ended with an error, see `error` and `httpStatus`. On Android, a job whose process died before it finished ends as `failed` with the error `orphaned`: a job belongs to the process that enqueued it. |

On iOS a job is `uploading` from the moment it is enqueued and never `queued`. A job whose task did not survive a force-quit is marked `failed` with the error `orphaned (app terminated)` on the first plugin call after launch.

### Retries (Android)

- A transfer is attempted at most 5 times for retryable failures: HTTP 408, 429, any 5xx, 308 on the data request, and network errors while a network is available. Each of these consumes an attempt, and the job fails after the fifth.
- Every other HTTP status, including all other 4xx codes, fails the job at once, and so do errors that are not network errors, such as a missing file or a revoked `content://` grant.
- A loss of connectivity does not consume an attempt. The job returns to `queued` and runs again once a network is available.
- A stop by the system does not consume an attempt either. The job returns to `queued`.
- A job that returns to `queued` without consuming an attempt is re-run 5 seconds after it is rescheduled, once the network constraint is met. While consecutive runs move no byte, the wait doubles (10, 20, 40, 80, 160 seconds) up to 300 seconds, and a run that sends more bytes than it started with resets it to 5 seconds. A counted retry instead waits on WorkManager's linear backoff of 10 seconds times the run count of the work request. For GCS resumable sessions the transfer continues from the committed offset.

### Events

```js
BackgroundUpload.addListener('uploadProgress', ({ jobId, bytesSent, totalBytes }) => {})
BackgroundUpload.addListener('uploadCompleted', ({ jobId, httpStatus }) => {})
BackgroundUpload.addListener('uploadFailed', ({ jobId, httpStatus, error }) => {})
```

Events are best effort. They can be missed while the app is not running, and a terminal event can arrive again after an app restart. Always reconcile with `getJobs()` on boot instead of relying on events. A `cancel` may or may not emit `uploadFailed`, depending on timing.

## Known limits

- A switch from Wi-Fi to mobile data breaks the connection while a network is still reported. That failure consumes an attempt.
- On Android 12 and later, a job that starts or re-runs while the app is in the background may run without a foreground service, and the system may delay it.
- For apps that target Android 15 (API 35) and later, Android limits the `dataSync` foreground service type to 6 hours in any 24. When the system stops the worker for that reason, the plugin handles it as any other stop by the system: the job goes back to `queued` without consuming an attempt.
- The Doinn app does not route iOS uploads through this plugin in this version. On iOS the plugin creates no background session and does no work until the first call to one of its methods.
- The retry rules above describe Android only. On iOS any status outside 2xx, and any transport error, fails the job at once.

## Release

```bash
yarn release:patch   # or release:minor / release:major
```

Bumps the version, pushes, and pushes the `vX.Y.Z` tag. Consumers pin the tag in their dependency URL.

## License

MIT
