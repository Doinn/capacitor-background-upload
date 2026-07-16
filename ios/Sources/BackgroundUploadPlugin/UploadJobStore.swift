import Foundation

struct UploadJob: Codable {
    let jobId: String
    var state: String // queued | uploading | uploaded | failed
    var bytesSent: Int64
    var totalBytes: Int64
    var httpStatus: Int?
    var error: String?
    var filePath: String?

    init(jobId: String, state: String) {
        self.jobId = jobId
        self.state = state
        self.bytesSent = 0
        self.totalBytes = 0
        self.httpStatus = nil
        self.error = nil
        self.filePath = nil
    }
}

final class UploadJobStore {
    private let defaults: UserDefaults
    private let queue = DispatchQueue(label: "com.doinn.backgroundupload.store")

    init() {
        defaults = UserDefaults(suiteName: "com.doinn.backgroundupload") ?? .standard
    }

    func save(_ job: UploadJob) {
        queue.sync {
            if let data = try? JSONEncoder().encode(job) {
                defaults.set(data, forKey: job.jobId)
            }
        }
    }

    func get(_ jobId: String) -> UploadJob? {
        queue.sync {
            guard let data = defaults.data(forKey: jobId) else { return nil }
            return try? JSONDecoder().decode(UploadJob.self, from: data)
        }
    }

    func getAll() -> [UploadJob] {
        queue.sync {
            defaults.dictionaryRepresentation().compactMap { _, value in
                guard let data = value as? Data else { return nil }
                return try? JSONDecoder().decode(UploadJob.self, from: data)
            }
        }
    }

    func remove(_ jobId: String) {
        queue.sync {
            defaults.removeObject(forKey: jobId)
        }
    }
}
