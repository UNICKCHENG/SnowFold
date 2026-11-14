import Foundation

/// 核心负责认证与条件写；宿主只发送一次请求，保留 HTTP 状态与 ETag。
final class URLSessionSyncTransport: SyncTransport, @unchecked Sendable {
    static let maximumResponseBytes = 32 * 1024 * 1024
    let timeout: TimeInterval
    private let protocolClasses: [AnyClass]?

    init(timeout: TimeInterval = 30, protocolClasses: [AnyClass]? = nil) {
        self.timeout = timeout
        self.protocolClasses = protocolClasses
    }

    func send(request: SyncRequest) throws -> SyncResponse {
        guard let url = URL(string: request.url) else { throw CoreError.SyncNetwork }
        var outgoing = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        outgoing.httpMethod = request.method
        outgoing.httpBody = request.body
        for header in request.headers { outgoing.setValue(header.value, forHTTPHeaderField: header.name) }

        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        if let protocolClasses { configuration.protocolClasses = protocolClasses }
        let transfer = Transfer()
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: transfer, delegateQueue: queue)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: outgoing)
        task.resume()
        guard transfer.finished.wait(timeout: .now() + timeout) == .success else {
            task.cancel()
            throw CoreError.SyncNetwork
        }
        return try transfer.result()
    }

    final class Transfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        let finished = DispatchSemaphore(value: 0)
        private let lock = NSLock()
        private var response: HTTPURLResponse?
        private var bytes = Data()
        private var outcome: Result<SyncResponse, CoreError>?

        func result() throws -> SyncResponse {
            lock.lock()
            defer { lock.unlock() }
            return try (outcome ?? .failure(.SyncNetwork)).get()
        }

        private func finish(_ result: Result<SyncResponse, CoreError>) {
            lock.lock()
            guard outcome == nil else { lock.unlock(); return }
            outcome = result
            lock.unlock()
            finished.signal()
        }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
            completionHandler(nil)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                        didReceive response: URLResponse,
                        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
            guard let response = response as? HTTPURLResponse,
                  response.expectedContentLength <= Int64(URLSessionSyncTransport.maximumResponseBytes) else {
                finish(.failure(.SyncNetwork))
                completionHandler(.cancel)
                return
            }
            self.response = response
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            guard data.count <= URLSessionSyncTransport.maximumResponseBytes - bytes.count else {
                finish(.failure(.SyncNetwork))
                dataTask.cancel()
                return
            }
            bytes.append(data)
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            guard error == nil, let response,
                  let status = UInt16(exactly: response.statusCode) else {
                finish(.failure(.SyncNetwork))
                return
            }
            let headers = response.allHeaderFields.map { SyncHeader(name: String(describing: $0.key), value: String(describing: $0.value)) }
            finish(.success(SyncResponse(status: status, headers: headers, body: bytes)))
        }
    }
}
