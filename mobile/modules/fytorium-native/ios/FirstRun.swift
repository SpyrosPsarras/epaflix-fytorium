import Foundation
import Security

// Keychain items survive an uninstall. A failed delete leaves first_run_done unset, so the next start retries.
func runFirstRun(_ flags: FlagStore, deleteKeychain: () -> OSStatus) -> Bool {
  if flags.get("first_run_done") { return flags.set(["first_run_done": true]) }
  guard flags.set(["push_reset_pending": true]) else { return false }
  let status = deleteKeychain()
  guard status == errSecSuccess || status == errSecItemNotFound else { return false }
  return flags.set(["first_run_done": true])
}

func deleteAllGenericPasswords() -> OSStatus {
  SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
}
