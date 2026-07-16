import Foundation

public final class BackgroundUploadManager: NSObject {
    public static let shared = BackgroundUploadManager()
    public static let sessionIdentifier = "com.doinn.backgroundupload"

    let store = UploadJobStore()
    public var backgroundCompletionHandler: (() -> Void)?

    var onProgress: ((String, Int64, Int64) -> Void)?
    var onCompleted: ((String, Int) -> Void)?
    var onFailed: ((String, Int?, String) -> Void)?

    // Zombie-sweep latch: entered synchronously in `sweepZombieJobs()` (called from
    // plugin `load()`, before the JS bridge can dispatch any new call), left once the
    // `session.getAllTasks` callback has finished reconciling the store. `getJobs`
    // waits on this so it never reads a store that the sweep is still rewriting.
    private let zombieSweepGroup = DispatchGroup()
    private let zombieSweepLock = NSLock()
    private var zombieSweepStarted = false

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(
            withIdentifier: BackgroundUploadManager.sessionIdentifier
        )
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    public func activateSession() {
        _ = session
    }

    /// Runs once per process launch. A force-quit cancels the background URLSession's
    /// tasks without the app process alive to run the `didCompleteWithError` delegate
    /// callback, so records can be stuck in `queued`/`uploading` forever. Any such
    /// record whose task is not among the session's live tasks is marked `failed` so
    /// the JS reconciler can clean it up.
    public func sweepZombieJobs() {
        zombieSweepLock.lock()
        guard !zombieSweepStarted else {
            zombieSweepLock.unlock()
            return
        }
        zombieSweepStarted = true
        zombieSweepGroup.enter()
        zombieSweepLock.unlock()

        session.getAllTasks { [weak self] tasks in
            guard let self = self else { return }
            let liveJobIds = Set(tasks.compactMap { $0.taskDescription })
            let candidates = self.store.getAll().filter { $0.state == "queued" || $0.state == "uploading" }
            for var job in candidates where !liveJobIds.contains(job.jobId) {
                job.state = "failed"
                job.error = "orphaned (app terminated)"
                self.store.save(job)
            }
            self.zombieSweepGroup.leave()
        }
    }

    /// Blocks (briefly) until `sweepZombieJobs()` has finished, so the first `getJobs`
    /// call from the reconciler never races the sweep. Falls back to proceeding anyway
    /// after the timeout so a stuck/slow session can never hang the reconciler.
    public func waitForZombieSweep(timeout: DispatchTimeInterval = .seconds(3)) {
        _ = zombieSweepGroup.wait(timeout: .now() + timeout)
    }

    func enqueue(jobId: String, filePath: String, url: String, headers: [String: String]) throws {
        guard let requestUrl = URL(string: url) else {
            throw NSError(domain: "BackgroundUpload", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Invalid url"])
        }
        let fileUrl: URL
        if filePath.hasPrefix("file://") {
            guard let parsed = URL(string: filePath) else {
                throw NSError(domain: "BackgroundUpload", code: 2,
                              userInfo: [NSLocalizedDescriptionKey: "Invalid filePath"])
            }
            fileUrl = parsed
        } else {
            fileUrl = URL(fileURLWithPath: filePath)
        }
        guard FileManager.default.fileExists(atPath: fileUrl.path) else {
            throw NSError(domain: "BackgroundUpload", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "File not found: \(fileUrl.path)"])
        }

        var request = URLRequest(url: requestUrl)
        request.httpMethod = "PUT"
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        var job = UploadJob(jobId: jobId, state: "uploading")
        let attrs = try? FileManager.default.attributesOfItem(atPath: fileUrl.path)
        job.totalBytes = (attrs?[.size] as? Int64) ?? 0
        job.filePath = fileUrl.path
        store.save(job)

        let task = session.uploadTask(with: request, fromFile: fileUrl)
        task.taskDescription = jobId
        task.resume()
    }

    func cancel(jobId: String) {
        session.getAllTasks { tasks in
            tasks.first { $0.taskDescription == jobId }?.cancel()
        }
    }
}

extension BackgroundUploadManager: URLSessionTaskDelegate, URLSessionDelegate {
    public func urlSession(
        _ session: URLSession, task: URLSessionTask,
        didSendBodyData bytesSent: Int64, totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        guard let jobId = task.taskDescription else { return }
        if var job = store.get(jobId) {
            job.bytesSent = totalBytesSent
            job.totalBytes = totalBytesExpectedToSend
            store.save(job)
        }
        onProgress?(jobId, totalBytesSent, totalBytesExpectedToSend)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let jobId = task.taskDescription else { return }
        var job = store.get(jobId) ?? UploadJob(jobId: jobId, state: "uploading")
        let status = (task.response as? HTTPURLResponse)?.statusCode

        if let error = error {
            job.state = "failed"
            job.error = error.localizedDescription
            job.httpStatus = status
            store.save(job)
            onFailed?(jobId, status, error.localizedDescription)
        } else if let status = status, (200...299).contains(status) {
            job.state = "uploaded"
            job.httpStatus = status
            job.bytesSent = job.totalBytes
            store.save(job)
            onCompleted?(jobId, status)
        } else {
            job.state = "failed"
            job.httpStatus = status
            job.error = "HTTP \(status ?? -1)"
            store.save(job)
            onFailed?(jobId, status, job.error ?? "unknown")
        }
    }

    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { [weak self] in
            self?.backgroundCompletionHandler?()
            self?.backgroundCompletionHandler = nil
        }
    }
}
