import Foundation
import Security

// Keychain items survive an uninstall. A failed delete leaves first_run_done unset, so the next start retries.
func runFirstRun(_ defaults: UserDefaults, deleteKeychain: () -> OSStatus) -> Bool {
  if defaults.bool(forKey: "first_run_done") { return true }
  defaults.set(true, forKey: "push_reset_pending")
  let status = deleteKeychain()
  guard status == errSecSuccess || status == errSecItemNotFound else { return false }
  defaults.set(true, forKey: "first_run_done")
  return true
}

func deleteAllGenericPasswords() -> OSStatus {
  SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
}
