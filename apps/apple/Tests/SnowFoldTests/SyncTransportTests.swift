import XCTest
@testable import SnowFold

private final class SyncURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var mode = "status"
    nonisolated(unsafe) static var seen: [URLRequest] = []
    static let lock = NSLock()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = Self.lock.withLock { Self.seen.append(request); return Self.mode }
        if mode == "timeout" { return }
        if mode == "network" {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let status = mode == "redirect" ? 302 : 412
        let headers = mode == "oversize" ? ["Content-Length": "33554433"] : ["ETag": "\"retained\"", "Location": "https://other.example.com/"]
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("response".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class SyncTransportTests: XCTestCase, @unchecked Sendable {
    private func run(_ mode: String, timeout: TimeInterval = 2) async throws -> SyncResponse {
        SyncURLProtocol.lock.withLock { SyncURLProtocol.mode = mode; SyncURLProtocol.seen = [] }
        return try await Task.detached {
            try URLSessionSyncTransport(timeout: timeout, protocolClasses: [SyncURLProtocol.self]).send(
                request: SyncRequest(method: "PUT", url: "https://sync.example.com/object", headers: [
                    SyncHeader(name: "If-Match", value: "\"v1\""),
                    SyncHeader(name: "Authorization", value: "test-only")
                ], body: Data("encrypted".utf8)))
        }.value
    }

    func testHTTPStatusETagAndConditionalHeadersAreUnchanged() async throws {
        let response = try await run("status")
        XCTAssertEqual(response.status, 412)
        XCTAssertEqual(response.body, Data("response".utf8))
        XCTAssertTrue(response.headers.contains { $0.name.lowercased() == "etag" && $0.value == "\"retained\"" })
        let requests = SyncURLProtocol.lock.withLock { SyncURLProtocol.seen }
        XCTAssertEqual(requests.count, 1, "宿主不重试 PUT")
        XCTAssertEqual(requests.first?.value(forHTTPHeaderField: "If-Match"), "\"v1\"")
    }

    func testRedirectIsReturnedWithoutSendingCredentialsToNewOrigin() async throws {
        let delegate = URLSessionSyncTransport.Transfer()
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let original = URLRequest(url: URL(string: "https://sync.example.com/object")!)
        let redirected = URLRequest(url: URL(string: "https://other.example.com/")!)
        let redirectResponse = HTTPURLResponse(url: original.url!, statusCode: 302, httpVersion: nil, headerFields: [:])!
        let refused = expectation(description: "重定向明确拒绝")
        delegate.urlSession(session, task: session.dataTask(with: original),
                            willPerformHTTPRedirection: redirectResponse, newRequest: redirected) { request in
            XCTAssertNil(request)
            refused.fulfill()
        }
        await fulfillment(of: [refused], timeout: 1)
        let response = try await run("redirect")
        XCTAssertEqual(response.status, 302)
        let requests = SyncURLProtocol.lock.withLock { SyncURLProtocol.seen }
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests.first?.url?.host, "sync.example.com")
    }

    func testTimeoutAndNetworkErrorsMapToSyncNetwork() async throws {
        XCTAssertEqual(URLSessionSyncTransport().timeout, 30)
        for mode in ["timeout", "network"] {
            do { _ = try await run(mode, timeout: 0.1); XCTFail("应拒绝超时或网络失败") }
            catch { XCTAssertEqual(error as? CoreError, .SyncNetwork) }
        }
    }

    func testResponseLargerThan32MiBIsRejected() async throws {
        do { _ = try await run("oversize"); XCTFail("超限响应必须拒绝") }
        catch { XCTAssertEqual(error as? CoreError, .SyncNetwork) }
    }
}
