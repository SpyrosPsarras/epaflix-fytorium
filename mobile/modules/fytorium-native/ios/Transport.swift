import Foundation

// N1 on iOS: no redirects, no cookies, no HTTP cache, 10 s request and 20 s total timeouts.
final class RefuseRedirects: NSObject, URLSessionTaskDelegate {
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                  newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
    completionHandler(nil)
  }
}

func makeSession() -> URLSession {
  let configuration = URLSessionConfiguration.ephemeral
  configuration.urlCache = nil
  configuration.httpCookieStorage = nil
  configuration.httpShouldSetCookies = false
  configuration.timeoutIntervalForRequest = 10
  configuration.timeoutIntervalForResource = 20
  return URLSession(configuration: configuration, delegate: RefuseRedirects(), delegateQueue: nil)
}

struct TransportResponse {
  let status: Int
  let headers: [String: String]
  let body: Data
}

final class Transport {
  private let api = makeSession()

  // A token exchange gets its own session, so it never reuses a pooled connection.
  func send(_ request: URLRequest, tokenEndpoint: Bool, done: @escaping (Result<TransportResponse, Error>) -> Void) {
    let client = tokenEndpoint ? makeSession() : api
    client.dataTask(with: request) { data, response, error in
      if tokenEndpoint { client.finishTasksAndInvalidate() }
      guard error == nil, let http = response as? HTTPURLResponse else {
        done(.failure(error ?? URLError(.badServerResponse)))
        return
      }
      var headers: [String: String] = [:]
      http.allHeaderFields.forEach { headers[String(describing: $0.key).lowercased()] = String(describing: $0.value) }
      done(.success(TransportResponse(status: http.statusCode, headers: headers, body: data ?? Data())))
    }.resume()
  }
}
