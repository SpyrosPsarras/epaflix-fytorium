import { error } from '@sveltejs/kit';
export function load() { error(403, 'Not a member. This instance already belongs to a household. Ask its owner for access.'); }
