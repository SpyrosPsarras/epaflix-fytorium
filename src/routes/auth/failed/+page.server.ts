import { error } from '@sveltejs/kit';
export function load() { error(400, 'Login failed or expired. Please try again.'); }
