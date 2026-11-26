import ExpoModulesCore
import Security

private let flagNames: Set<String> = ["push_ready", "push_reset_pending", "notifications_asked", "first_run_done"]

final class RefuseRedirects: NSObject, URLSessionTaskDelegate {
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                  newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
    completionHandler(nil)
  }
}

private func session(keepAlive: Bool) -> URLSession {
  let configuration = URLSessionConfiguration.ephemeral
  configuration.urlCache = nil
  configuration.httpCookieStorage = nil
  configuration.httpShouldSetCookies = false
  configuration.timeoutIntervalForRequest = 10
  configuration.timeoutIntervalForResource = 20
  if !keepAlive { configuration.httpAdditionalHeaders = ["Connection": "close"] }
  return URLSession(configuration: configuration, delegate: RefuseRedirects(), delegateQueue: nil)
}

struct RequestOptions: Record {
  @Field var method: String = "GET"
  @Field var url: String = ""
  @Field var headers: [String: String] = [:]
  @Field var body: String? = nil
  @Field var tokenEndpoint: Bool = false
  @Field var binary: Bool = false
}

public class FytoriumNativeModule: Module {
  private let api = session(keepAlive: true)
  private let tokenSession = session(keepAlive: false)
  private let defaults = UserDefaults.standard

  public func definition() -> ModuleDefinition {
    Name("FytoriumNative")
    Events("liveRefresh", "openHome")

    Constant("isDebugBuild") {
      #if DEBUG
      return true
      #else
      return false
      #endif
    }

    Function("firstRun") { () -> Bool in
      if self.defaults.bool(forKey: "first_run_done") { return false }
      self.defaults.set(true, forKey: "push_reset_pending")
      SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
      self.defaults.set(true, forKey: "first_run_done")
      return true
    }
    Function("restoredFromSavedState") { false }
    Function("getFlag") { (name: String) -> Bool in self.defaults.bool(forKey: name) }
    Function("setFlag") { (name: String, value: Bool) throws in
      guard flagNames.contains(name) else { throw Exception(name: "ERR_FLAG", description: "Unknown flag") }
      self.defaults.set(value, forKey: name)
    }
    Function("obtainiumPackage") { () -> String? in nil }
    Function("consumeOpenHome") { false }
    Function("debugLaunchArgument") { (name: String) -> String? in
      #if DEBUG
      return UserDefaults.standard.string(forKey: name)
      #else
      return nil
      #endif
    }
    Function("schedulePeriodicRefresh") {}
    Function("cancelRefresh") {}

    AsyncFunction("request") { (options: RequestOptions, promise: Promise) in
      guard let url = URL(string: options.url) else { promise.reject("ERR_REQUEST", "Invalid request"); return }
      var request = URLRequest(url: url)
      request.httpMethod = options.method
      options.headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
      request.httpBody = options.body?.data(using: .utf8)
      (options.tokenEndpoint ? self.tokenSession : self.api).dataTask(with: request) { data, response, error in
        guard error == nil, let http = response as? HTTPURLResponse else { promise.reject("ERR_NETWORK", "Network request failed"); return }
        let bytes = data ?? Data()
        var headers: [String: String] = [:]
        http.allHeaderFields.forEach { headers[String(describing: $0.key).lowercased()] = String(describing: $0.value) }
        promise.resolve(["status": http.statusCode, "headers": headers,
                         "body": options.binary ? bytes.base64EncodedString() : String(decoding: bytes, as: UTF8.self)])
      }.resume()
    }
  }
}
