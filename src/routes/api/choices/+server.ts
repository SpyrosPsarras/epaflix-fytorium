import { json } from '@sveltejs/kit';
import { plantChoices, plantLabels, plantBooleans, plantNumbers } from '$lib/plants';
import { healthValues } from '$lib/photos';
import { nameLanguages } from '$lib/names';
import type { RequestHandler } from './$types';

// Policy restricts these editing choices to owner/family. Web and app share the same lists.
export const GET: RequestHandler = () => json({ plant: {
  choices: plantChoices, labels: plantLabels, booleans: plantBooleans, numbers: plantNumbers, health: healthValues,
}, nameLanguages });
