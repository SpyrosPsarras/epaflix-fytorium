import { decideRestore } from '../shell/restore';

const saved = { member: 'm1.owner', state: { index: 1 } };

test('a recreated activity for the same member and role gets its pages back', () => {
  expect(decideRestore({ restored: true, member: 'm1.owner', saved })).toEqual({ state: { index: 1 }, wipe: false });
});

test('another member or role starts at Today and drops the drafts', () => {
  expect(decideRestore({ restored: true, member: 'm1.sitter', saved })).toEqual({ wipe: true });
  expect(decideRestore({ restored: true, member: 'm2.owner', saved })).toEqual({ wipe: true });
});

test('a cold start or a second mount in the same process starts at Today', () => {
  expect(decideRestore({ restored: false, member: 'm1.owner', saved })).toEqual({ wipe: true });
});

test('before the member is known nothing is restored', () => {
  expect(decideRestore({ restored: true, member: null, saved })).toEqual({ wipe: true });
});
