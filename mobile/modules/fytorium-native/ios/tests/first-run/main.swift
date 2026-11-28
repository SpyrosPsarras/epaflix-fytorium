import Foundation
import Security

func check(_ condition: Bool, _ message: String) {
  if !condition { print("FAIL: \(message)"); exit(1) }
}

let folder = FileManager.default.temporaryDirectory.appendingPathComponent("fytorium-flags-\(UUID().uuidString)")
try! FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
func store(_ name: String) -> FlagStore { FlagStore(url: folder.appendingPathComponent(name)) }

// First run.
let flags = store("first-run.json")
var results: [OSStatus] = [errSecMissingEntitlement, errSecSuccess]
var calls = 0
let delete: () -> OSStatus = { calls += 1; return results.removeFirst() }
check(!runFirstRun(flags, deleteKeychain: delete), "a failed Keychain delete reports failure")
check(!flags.get("first_run_done"), "a failed Keychain delete leaves first_run_done unset")
check(flags.get("push_reset_pending"), "push_reset_pending is set before the delete")
check(runFirstRun(flags, deleteKeychain: delete), "the retry succeeds")
check(store("first-run.json").get("first_run_done"), "the retry marks first run done on disk")
check(runFirstRun(flags, deleteKeychain: { calls += 1; return errSecIO }), "a finished first run does not touch the Keychain")
check(calls == 2, "the Keychain delete ran once per unfinished start")
check(runFirstRun(store("fresh.json"), deleteKeychain: { errSecItemNotFound }), "an empty Keychain counts as cleaned")

// A session end writes its three flags in one file replacement; a restart reads all of them.
let session = store("session.json")
session.set(["push_ready": true])
check(session.set(["push_ready": false, "push_reset_pending": true, "session_wipe_pending": true]), "the session-end write succeeds")
let restarted = store("session.json")
check(!restarted.get("push_ready") && restarted.get("push_reset_pending") && restarted.get("session_wipe_pending"), "a restart sees every session-end flag")

// A write that cannot reach disk reports failure and changes nothing.
let locked = folder.appendingPathComponent("locked")
try! FileManager.default.createDirectory(at: locked, withIntermediateDirectories: true)
let blocked = FlagStore(url: locked.appendingPathComponent("flags.json"))
blocked.set(["push_ready": true])
try! FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: locked.path)
check(!blocked.set(["push_ready": false, "session_wipe_pending": true]), "a write that cannot reach disk reports failure")
check(blocked.get("push_ready") && !blocked.get("session_wipe_pending"), "a failed write leaves the flags as they were")
try! FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: locked.path)
// N9: the SQLite folder is excluded from backup, created if missing.
let sqlite = folder.appendingPathComponent("SQLite")
check(excludeFromBackup(sqlite), "the SQLite folder is marked excluded from backup")
check((try? sqlite.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup) == true, "the backup exclusion is stored on the folder")
print("FirstRun, flag store and backup exclusion tests passed")
