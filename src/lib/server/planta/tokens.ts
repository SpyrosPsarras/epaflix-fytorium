import { error } from '@sveltejs/kit';

export function plantaTokens(result: any): { accessToken: string; refreshToken: string; tokenType: 'Bearer' } {
  const tokens = result?.data ?? result;
  if (typeof tokens?.accessToken !== 'string' || !tokens.accessToken || typeof tokens.refreshToken !== 'string' || !tokens.refreshToken || typeof tokens.tokenType !== 'string' || tokens.tokenType.toLowerCase() !== 'bearer') error(502, 'Planta returned invalid authorization.');
  return { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, tokenType: 'Bearer' };
}
