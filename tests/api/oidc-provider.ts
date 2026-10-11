import { createServer, type Server } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { exportJWK, generateKeyPair, SignJWT } from 'jose';

export const TEST_CLIENT_SECRET = 'synthetic-oidc-secret';

export async function startOidcProvider() {
  const { publicKey, privateKey } = await generateKeyPair('RS256');
  const jwk = { ...await exportJWK(publicKey), kid: 'test-key', alg: 'RS256', use: 'sig' };
  let issuer = '';
  let subject = 'synthetic-owner';
  const codes = new Map<string, { subject: string; nonce: string; challenge: string; redirect: string }>();
  const server = createServer(async (req, res) => {
    const url = new URL(req.url!, issuer);
    const json = (data: unknown) => { res.setHeader('content-type', 'application/json'); res.end(JSON.stringify(data)); };
    if (url.pathname === '/.well-known/openid-configuration') {
      json({ issuer, authorization_endpoint: issuer + '/authorize', token_endpoint: issuer + '/token', jwks_uri: issuer + '/jwks', response_types_supported: ['code'], subject_types_supported: ['public'], id_token_signing_alg_values_supported: ['RS256'], token_endpoint_auth_methods_supported: ['client_secret_basic'], code_challenge_methods_supported: ['S256'] });
    } else if (url.pathname === '/jwks') {
      json({ keys: [jwk] });
    } else if (url.pathname === '/authorize') {
      if (url.searchParams.get('code_challenge_method') !== 'S256' || url.searchParams.get('client_id') !== 'test-client') {
        res.writeHead(400).end(); return;
      }
      const code = randomUUID();
      codes.set(code, { subject, nonce: url.searchParams.get('nonce')!, challenge: url.searchParams.get('code_challenge')!, redirect: url.searchParams.get('redirect_uri')! });
      const redirect = new URL(url.searchParams.get('redirect_uri')!);
      redirect.searchParams.set('code', code);
      redirect.searchParams.set('state', url.searchParams.get('state')!);
      res.writeHead(302, { location: redirect.href }).end();
    } else if (url.pathname === '/token') {
      let body = '';
      for await (const chunk of req) body += chunk;
      const params = new URLSearchParams(body);
      const code = codes.get(params.get('code')!);
      codes.delete(params.get('code')!);
      const challenge = createHash('sha256').update(params.get('code_verifier') ?? '').digest('base64url');
      const basic = Buffer.from(req.headers.authorization?.slice(6) ?? '', 'base64').toString().split(':').map(value => decodeURIComponent(value.replaceAll('+', ' ')));
      if (!code || code.challenge !== challenge || params.get('redirect_uri') !== code.redirect || basic[0] !== 'test-client' || basic[1] !== TEST_CLIENT_SECRET) {
        res.statusCode = 400; json({ error: 'invalid_grant' }); return;
      }
      const idToken = await new SignJWT({ nonce: code.nonce }).setProtectedHeader({ alg: 'RS256', kid: 'test-key' }).setIssuer(issuer).setAudience('test-client').setSubject(code.subject).setIssuedAt().setExpirationTime('5m').sign(privateKey);
      json({ access_token: 'synthetic-access-token', token_type: 'Bearer', expires_in: 300, id_token: idToken });
    } else { res.writeHead(404).end(); }
  });
  const port = await listen(server);
  issuer = `http://127.0.0.1:${port}`;
  return { issuer, server, setSubject(value: string) { subject = value; } };
}

export async function listen(server: Server): Promise<number> {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('No listening address');
  return address.port;
}

export async function close(server: Server) {
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}
