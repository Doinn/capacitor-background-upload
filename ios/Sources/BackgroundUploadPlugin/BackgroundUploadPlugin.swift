import Capacitor
import Foundation

@objc(BackgroundUploadPlugin)
public class BackgroundUploadPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BackgroundUploadPlugin"
    public let jsName = "BackgroundUpload"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enqueueUpload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "prepareFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getJobs", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "acknowledge", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cleanupOrphans", returnType: CAPPluginReturnPromise)
    ]

    private let manager = BackgroundUploadManager.shared

    private let activationLock = NSLock()
    private var activated = false

    override public func load() {
        manager.onProgress = { [weak self] jobId, sent, total in
            self?.notifyListeners("uploadProgress", data: [
                "jobId": jobId, "bytesSent": sent, "totalBytes": total
            ])
        }
        manager.onCompleted = { [weak self] jobId, status in
            self?.notifyListeners("uploadCompleted", data: [
                "jobId": jobId, "httpStatus": status
            ])
        }
        manager.onFailed = { [weak self] jobId, status, message in
            var data: [String: Any] = ["jobId": jobId, "error": message]
            if let status = status { data["httpStatus"] = status }
            self?.notifyListeners("uploadFailed", data: data)
        }
    }

    /// Creates the background session and sweeps zombie jobs (queued/uploading records
    /// whose background task did not survive a force-quit) on the first plugin call, so an
    /// app that installs the plugin and never calls it does nothing at launch. The sweep
    /// enters its latch synchronously inside this call, before the calling method touches
    /// the store, so a fresh enqueue cannot be mistaken for a zombie. The lock makes the
    /// once-only guard safe when two calls arrive on different threads.
    private func ensureActivated() {
        activationLock.lock()
        defer { activationLock.unlock() }
        if activated { return }
        activated = true
        manager.activateSession()
        manager.sweepZombieJobs()
    }

    @objc func enqueueUpload(_ call: CAPPluginCall) {
        ensureActivated()
        guard let jobId = call.getString("jobId"),
              let filePath = call.getString("filePath"),
              let url = call.getString("url") else {
            call.reject("jobId, filePath and url are required")
            return
        }
        var headers: [String: String] = [:]
        if let headersObj = call.getObject("headers") {
            for (key, value) in headersObj {
                if let stringValue = value as? String {
                    headers[key] = stringValue
                }
            }
        }
        do {
            try manager.enqueue(jobId: jobId, filePath: filePath, url: url, headers: headers)
            call.resolve()
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func prepareFile(_ call: CAPPluginCall) {
        ensureActivated()
        guard let filePath = call.getString("filePath") else {
            call.reject("filePath is required")
            return
        }
        let imageMaxDimension = call.getInt("imageMaxDimension")
        let imageQuality = call.getDouble("imageQuality")
        let deleteSource = call.getBool("deleteSource") ?? false

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let prepared = try UploadFilePreparer.prepare(
                    filePath: filePath,
                    imageMaxDimension: imageMaxDimension,
                    imageQuality: imageQuality,
                    deleteSource: deleteSource
                )
                var result: [String: Any] = ["path": prepared.path, "size": prepared.size]
                if let mimeType = prepared.mimeType { result["mimeType"] = mimeType }
                call.resolve(result)
            } catch {
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func getJobs(_ call: CAPPluginCall) {
        ensureActivated()
        // Must not race the startup zombie sweep (see manager.sweepZombieJobs()).
        manager.waitForZombieSweep()
        let jobs = manager.store.getAll().map { job -> [String: Any] in
            var obj: [String: Any] = [
                "jobId": job.jobId,
                "state": job.state,
                "bytesSent": job.bytesSent,
                "totalBytes": job.totalBytes
            ]
            if let status = job.httpStatus { obj["httpStatus"] = status }
            if let error = job.error { obj["error"] = error }
            return obj
        }
        call.resolve(["jobs": jobs])
    }

    @objc func acknowledge(_ call: CAPPluginCall) {
        ensureActivated()
        guard let jobId = call.getString("jobId") else {
            call.reject("jobId is required")
            return
        }
        if let job = manager.store.get(jobId), let filePath = job.filePath,
           UploadFilePreparer.isInsideCacheDir(filePath) {
            try? FileManager.default.removeItem(atPath: filePath)
        }
        manager.store.remove(jobId)
        call.resolve()
    }

    @objc func cancel(_ call: CAPPluginCall) {
        ensureActivated()
        guard let jobId = call.getString("jobId") else {
            call.reject("jobId is required")
            return
        }
        manager.cancel(jobId: jobId)
        call.resolve()
    }

    @objc func cleanupOrphans(_ call: CAPPluginCall) {
        ensureActivated()
        let maxAgeMs = call.getInt("maxAgeMs") ?? UploadFilePreparer.defaultOrphanMaxAgeMs
        let referencedPaths = Set(manager.store.getAll().compactMap { $0.filePath })

        DispatchQueue.global(qos: .utility).async {
            let deleted = UploadFilePreparer.cleanupOrphans(
                referencedPaths: referencedPaths,
                maxAgeMs: maxAgeMs
            )
            call.resolve(["deleted": deleted])
        }
    }
}
