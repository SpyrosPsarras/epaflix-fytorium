jest.mock('expo-crypto', () => {
  const mockCrypto = require('node:crypto');
  return {
    CryptoDigestAlgorithm: { SHA256: 'SHA-256' },
    getRandomBytes: (size) => new Uint8Array(mockCrypto.randomBytes(size)),
    digest: async (_algorithm, data) => {
      const hash = mockCrypto.createHash('sha256').update(Buffer.from(data)).digest();
      return hash.buffer.slice(hash.byteOffset, hash.byteOffset + hash.byteLength);
    },
  };
});

jest.mock('expo-secure-store', () => {
  const items = new Map();
  let failDeletes = false;
  return {
    __failDeletes: (value) => { failDeletes = value; },
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: 1,
    WHEN_UNLOCKED_THIS_DEVICE_ONLY: 2,
    getItem: (key) => items.get(key) ?? null,
    setItemAsync: async (key, value) => { items.set(key, value); },
    deleteItemAsync: async (key) => {
      if (failDeletes) throw new Error('keystore busy');
      items.delete(key);
    },
    __items: items,
  };
});

jest.mock('expo-sqlite/kv-store', () => {
  const items = new Map();
  return {
    __esModule: true,
    default: {
      getItemSync: (key) => items.get(key) ?? null,
      setItemSync: (key, value) => { items.set(key, value); },
      removeItemSync: (key) => { items.delete(key); },
    },
  };
});
