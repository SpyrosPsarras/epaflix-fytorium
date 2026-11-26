import Foundation
import Security

func check(_ condition: Bool, _ message: String) {
  if !condition { print("FAIL: \(message)"); exit(1) }
}

let defaults = UserDefaults(suiteName: "fytorium-first-run-test-\(UUID().uuidString)")!
var results: [OSStatus] = [errSecMissingEntitlement, errSecSuccess]
var calls = 0
let delete: () -> OSStatus = { calls += 1; return results.removeFirst() }

check(!runFirstRun(defaults, deleteKeychain: delete), "a failed Keychain delete reports failure")
check(!defaults.bool(forKey: "first_run_done"), "a failed Keychain delete leaves first_run_done unset")
check(defaults.bool(forKey: "push_reset_pending"), "push_reset_pending is set before the delete")
check(runFirstRun(defaults, deleteKeychain: delete), "the retry succeeds")
check(defaults.bool(forKey: "first_run_done"), "the retry marks first run done")
check(runFirstRun(defaults, deleteKeychain: { calls += 1; return errSecIO }), "a finished first run does not touch the Keychain")
check(calls == 2, "the Keychain delete ran once per unfinished start")

let fresh = UserDefaults(suiteName: "fytorium-first-run-test-\(UUID().uuidString)")!
check(runFirstRun(fresh, deleteKeychain: { errSecItemNotFound }), "an empty Keychain counts as cleaned")
print("FirstRun tests passed")
