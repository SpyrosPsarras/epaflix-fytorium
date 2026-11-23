import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
const harness = createApiHarness();
let owner: Awaited<ReturnType<typeof harness.login>>;
beforeAll(async () => { owner = await harness.login(); });
test('only owners can change family and sitter roles; protected owners cannot change', async () => {
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'family' })).json();
  const family = await harness.login('synthetic-role-member', {}, invite.link.split('/').at(-1));
  const id = (await (await family.request('/api/me')).json()).member.id;
  const list = await (await owner.request('/api/household/members')).json();
  expect(list.find((m: any) => m.id === id)).toMatchObject({ role_options: ['family', 'sitter'], can_remove: true });
  const self = list.find((m: any) => m.role === 'owner');
  expect(self).toMatchObject({ role_options: [], can_remove: false });
  expect((await family.request('/api/household/members/' + id, 'PUT', { role: 'sitter', plant_ids: [] })).status).toBe(403);
  expect((await owner.request('/api/household/members/' + self.id, 'PUT', { role: 'sitter', plant_ids: [] })).status).toBe(409);
  expect((await owner.request('/api/household/members/' + id, 'PUT', { role: 'owner' })).status).toBe(422);
  expect((await owner.request('/api/household/members/' + id, 'PUT', { role: 'sitter', plant_ids: [] })).status).toBe(200);
  expect((await family.request('/api/household/members/' + id, 'PUT', { role: 'family' })).status).toBe(403);
  expect((await family.request('/api/household/members')).status).toBe(403);
  expect(await (await family.request('/api/me')).json()).toMatchObject({ member: { role: 'sitter' } });
  expect((await owner.request('/api/household/members/' + id, 'PUT', { role: 'family' })).status).toBe(200);
  expect(await (await family.request('/api/me')).json()).toMatchObject({ member: { role: 'family' } });
});

// W5a SV1: the More header and the sitter's sentence name the member and the household owner.
test('/api/me names the member and the household owner for every role', async () => {
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'family' })).json();
  const member = await harness.login('synthetic-named-member', { name: 'Synthetic Ada' }, invite.link.split('/').at(-1));
  const ownerMe = await (await owner.request('/api/me')).json();
  expect(ownerMe).toMatchObject({ member: { role: 'owner', display_name: 'Test member' }, household: { owner_name: 'Test member' } });
  expect(await (await member.request('/api/me')).json()).toMatchObject({ member: { role: 'family', display_name: 'Synthetic Ada' }, household: { owner_name: 'Test member' } });
  const id = (await (await member.request('/api/me')).json()).member.id;
  expect((await owner.request('/api/household/members/' + id, 'PUT', { role: 'sitter', plant_ids: [] })).status).toBe(200);
  const sitterMe = await (await member.request('/api/me')).json();
  expect(sitterMe).toEqual({ language: expect.any(String), member: { id, role: 'sitter', display_name: 'Synthetic Ada' },
    household: { id: ownerMe.household.id, name: ownerMe.household.name, owner_name: 'Test member' } });
});
