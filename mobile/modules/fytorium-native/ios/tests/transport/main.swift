import Foundation

func check(_ condition: Bool, _ message: String) {
  if !condition { print("FAIL: \(message)"); exit(1) }
}

let base = CommandLine.arguments[1]
let transport = Transport()

func send(_ path: String, method: String = "GET", token: Bool = false) -> Result<TransportResponse, Error> {
  var request = URLRequest(url: URL(string: base + path)!)
  request.httpMethod = method
  if method == "POST" { request.httpBody = Data("{}".utf8); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
  let done = DispatchSemaphore(value: 0)
  var result: Result<TransportResponse, Error>!
  transport.send(request, tokenEndpoint: token) { result = $0; done.signal() }
  done.wait()
  return result
}

struct Entry: Decodable { let path: String; let method: String; let port: Int; let cookie: String? }
func log() -> [Entry] {
  let data = try! Data(contentsOf: URL(string: base + "/__log")!)
  return try! JSONDecoder().decode([Entry].self, from: data)
}
func count(_ path: String) -> Int { log().filter { $0.path == path }.count }

check((try? send("/redirect").get())?.status == 302, "a redirect is returned, not followed")
check(count("/target") == 0, "the redirect target was never requested")

_ = send("/set-cookie")
_ = send("/echo")
check(log().last { $0.path == "/echo" }?.cookie == nil, "a cookie is neither stored nor sent")

check((try? send("/busy", method: "POST").get())?.status == 503, "a 503 is returned")
check(count("/busy") == 1, "a 503 with Retry-After: 0 is not followed")

if case .success = send("/drop", method: "POST", token: true) { check(false, "a dropped token response fails") }
check(count("/drop") == 1, "a dropped token response is not resent")
_ = send("/ok")
if case .success = send("/drop", method: "POST") { check(false, "a dropped write response fails") }
check(count("/drop") == 2, "a dropped write on a pooled connection is not resent")

_ = send("/ok")
_ = send("/ok")
let pooled = log().filter { $0.path == "/ok" }.suffix(2).map(\.port)
check(pooled[0] == pooled[1], "ordinary requests reuse a pooled connection")
_ = send("/token", method: "POST", token: true)
_ = send("/token", method: "POST", token: true)
let tokens = log().filter { $0.path == "/token" }.map(\.port)
check(!pooled.contains(tokens[0]) && !pooled.contains(tokens[1]) && tokens[0] != tokens[1], "each token exchange uses a fresh connection")

print("Transport tests passed: no redirect, no cookies, no 503 follow-up, no resend of dropped responses, fresh token connections")
