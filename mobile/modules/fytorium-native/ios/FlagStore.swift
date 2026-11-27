import Foundation

let flagNames: Set<String> = ["push_ready", "push_reset_pending", "notifications_asked", "first_run_done", "session_wipe_pending"]

// All flags live in one file that is replaced atomically, so a session end that writes several flags is one durable write.
final class FlagStore {
  private let url: URL
  private var values: [String: Bool]

  init(url: URL) {
    self.url = url
    if let data = try? Data(contentsOf: url), let saved = try? JSONDecoder().decode([String: Bool].self, from: data) {
      values = saved
    } else {
      values = [:]
    }
  }

  static func standard() -> FlagStore {
    let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    return FlagStore(url: folder.appendingPathComponent("fytorium-platform.json"))
  }

  func get(_ name: String) -> Bool { values[name] ?? false }

  // Returns whether the new values reached disk; on failure the stored and in-memory flags stay as they were.
  @discardableResult func set(_ changes: [String: Bool]) -> Bool {
    let next = values.merging(changes) { $1 }
    guard let data = try? JSONEncoder().encode(next) else { return false }
    do {
      try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      var target = url
      var keep = URLResourceValues()
      keep.isExcludedFromBackup = true
      try? target.setResourceValues(keep)
    } catch {
      return false
    }
    values = next
    return true
  }
}
