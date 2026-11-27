import ExpoModulesCore
import Security

struct RequestOptions: Record {
  @Field var method: String = "GET"
  @Field var url: String = ""
  @Field var headers: [String: String] = [:]
  @Field var body: String? = nil
  @Field var tokenEndpoint: Bool = false
  @Field var binary: Bool = false
}

public class FytoriumNativeModule: Module {
  private let transport = Transport()
  private let flags = FlagStore.standard()

  public func definition() -> ModuleDefinition {
    Name("FytoriumNative")
    Events("liveRefresh", "openHome", "redirect")

    Constant("isDebugBuild") {
      #if DEBUG
      return true
      #else
      return false
      #endif
    }

    Function("firstRun") { () -> Bool in runFirstRun(self.flags, deleteKeychain: deleteAllGenericPasswords) }
    Function("consumeRestoredFromSavedState") { false }
    Function("moveTaskToBack") { false }
    Function("setBackHandled") { (_: Bool) in }
    // N9: the app's SQLite folder is kept out of iCloud and device backups.
    Function("excludeFromBackup") { (path: String) -> Bool in excludeFromBackup(URL(fileURLWithPath: path)) }
    Function("getFlag") { (name: String) -> Bool in self.flags.get(name) }
    Function("setFlags") { (values: [String: Bool]) throws -> Bool in
      guard values.keys.allSatisfy({ flagNames.contains($0) }) else { throw Exception(name: "ERR_FLAG", description: "Unknown flag") }
      return self.flags.set(values)
    }
    Function("setFlag") { (name: String, value: Bool) throws in
      guard flagNames.contains(name) else { throw Exception(name: "ERR_FLAG", description: "Unknown flag") }
      self.flags.set([name: value])
    }
    Function("obtainiumPackage") { () -> String? in nil }
    Function("openObtainium") { false }
    Function("takeRedirects") { () -> [String] in [] }
    Function("openLogin") { (_: String) in false }
    Function("consumeOpenHome") { false }
    Function("debugLaunchArgument") { (name: String) -> String? in
      #if DEBUG
      return UserDefaults.standard.string(forKey: name)
      #else
      return nil
      #endif
    }
    Function("debugLog") { (_: String) in }
    Function("schedulePeriodicRefresh") {}
    Function("cancelRefresh") {}

    AsyncFunction("request") { (options: RequestOptions, promise: Promise) in
      guard let url = URL(string: options.url) else { promise.reject("ERR_REQUEST", "Invalid request"); return }
      var request = URLRequest(url: url)
      request.httpMethod = options.method
      options.headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
      request.httpBody = options.body?.data(using: .utf8)
      self.transport.send(request, tokenEndpoint: options.tokenEndpoint) { result in
        switch result {
        case .failure: promise.reject("ERR_NETWORK", "Network request failed")
        case .success(let response):
          promise.resolve(["status": response.status, "headers": response.headers,
                           "body": options.binary ? response.body.base64EncodedString() : String(decoding: response.body, as: UTF8.self)])
        }
      }
    }
  }
}
